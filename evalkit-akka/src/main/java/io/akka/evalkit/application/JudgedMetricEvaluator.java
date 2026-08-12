package io.akka.evalkit.application;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.ledger.LedgerClient;
import io.akka.evalkit.domain.InconclusiveScore;
import io.akka.evalkit.domain.Observation;
import io.akka.evalkit.evaluation.MetricEvaluator;
import io.akka.evalkit.metric.Finding;
import io.akka.evalkit.metric.FindingsReply;

import java.util.List;
import java.util.Objects;

/**
 * A metric whose findings come from a model, running against a recorded interaction.
 *
 * <p>{@code ToolPermission} works its findings out from the record and calls nothing.
 * {@code TurnRelevancy}, {@code TurnFaithfulness} and {@code CitationFaithfulness} ask a model
 * about every exchange, claim or citation, so each subclass states what to ask and what part
 * of the run to ask about.
 *
 * <p>A run with nothing to judge produces an empty list without calling a model, and the
 * metric decides what that means. A model that answers unreadably throws {@link InconclusiveScore},
 * which reaches the ledger as an inconclusive evaluation.
 */
public abstract class JudgedMetricEvaluator extends MetricEvaluator {

    private final ComponentClient componentClient;

    protected JudgedMetricEvaluator(LedgerClient ledger, ComponentClient componentClient) {
        super(ledger);
        this.componentClient = Objects.requireNonNull(componentClient, "componentClient");
    }

    /** What the model is asked to decide. */
    protected abstract String instructions();

    /**
     * The part of the run being judged, or empty when there is nothing to judge.
     *
     * <p>An empty string calls no model. A metric reading it returns whatever it returns for
     * an empty finding list, which for these three is stated on each metric.
     */
    public abstract String material(Observation observation);

    @Override
    protected final List<Finding> judge(Observation observation) {
        String material = material(observation);
        if (material == null || material.isBlank()) return List.of();

        String reply = componentClient.forAgent()
            .inSession(observation.interaction().interactionId())
            .method(MetricJudge::judge)
            .invoke(new MetricJudge.JudgeRequest(instructions(), material));

        return FindingsReply.read(reply);
    }
}
