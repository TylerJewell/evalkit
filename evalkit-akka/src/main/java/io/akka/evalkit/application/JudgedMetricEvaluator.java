package io.akka.evalkit.application;

import akka.javasdk.client.ComponentClient;
import akka.javasdk.ledger.LedgerClient;
import io.akka.evalkit.domain.NoVerdict;
import io.akka.evalkit.domain.Recording;
import io.akka.evalkit.evaluation.MetricEvaluator;
import io.akka.evalkit.metric.Judgement;
import io.akka.evalkit.metric.JudgementReply;

import java.util.List;
import java.util.Objects;

/**
 * A metric whose judgements come from a model, running against a recorded interaction.
 *
 * <p>{@code ToolPermission} works its judgements out from the record and calls nothing.
 * {@code TurnRelevancy}, {@code TurnFaithfulness} and {@code CitationFaithfulness} ask a model
 * about every exchange, claim or citation, so each subclass states what to ask and what part
 * of the run to ask about.
 *
 * <p>A run with nothing to judge produces an empty list without calling a model, and the
 * metric decides what that means. A model that answers unreadably throws {@link NoVerdict},
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
     * an empty judgement list, which for these three is stated on each metric.
     */
    public abstract String material(Recording recording);

    @Override
    protected final List<Judgement> judge(Recording recording) {
        String material = material(recording);
        if (material == null || material.isBlank()) return List.of();

        String reply = componentClient.forAgent()
            .inSession(recording.interaction().interactionId())
            .method(MetricJudge::judge)
            .invoke(new MetricJudge.JudgeRequest(instructions(), material));

        return JudgementReply.read(reply);
    }
}
