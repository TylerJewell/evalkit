package io.akka.evalkit.evaluation;

import akka.javasdk.ledger.InteractionRecord;
import akka.javasdk.ledger.LedgerClient;
import io.akka.evalkit.domain.Recording;
import io.akka.evalkit.domain.Scorer;
import io.akka.evalkit.metric.Judgement;
import io.akka.evalkit.metric.Metric;

import java.util.List;

/**
 * Runs a {@link Metric} against a recorded interaction.
 *
 * <p>A metric reads what the run did. It reads no expected outcome, so an evaluator built on
 * one scores an interaction the platform recorded from live traffic as readily as one a
 * campaign caused. {@link #expectedOutcome} is empty here for that reason, and a judge that
 * needs a scenario uses {@link ScorerEvaluator} directly.
 *
 * <p>{@link Metric#outcome(List)} decides what an empty judgement list means. A metric with
 * nothing to measure returns {@code Unscoreable}, which reaches the ledger as
 * {@code Inconclusive} and stays out of a pass rate.
 */
public abstract class MetricEvaluator extends ScorerEvaluator {

    protected MetricEvaluator(LedgerClient ledger) {
        super(ledger);
    }

    /** The metric this evaluator scores with. */
    protected abstract Metric metric();

    /**
     * The judgements the metric aggregates.
     *
     * <p>A deterministic metric works them out from the record. A metric that needs a model
     * asks one here, and a subclass supplies the caller that reaches it.
     */
    protected abstract List<Judgement> judge(Recording recording);

    @Override
    protected final Scorer scorer() {
        return recording -> metric().outcome(judge(recording));
    }

    /** A metric reads what the run did, and no interaction record states what was wanted. */
    @Override
    protected final String expectedOutcome(InteractionRecord record) {
        return "";
    }
}
