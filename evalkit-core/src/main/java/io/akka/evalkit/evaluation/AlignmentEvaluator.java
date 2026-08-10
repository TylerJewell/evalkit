package io.akka.evalkit.evaluation;

import akka.javasdk.ledger.InteractionRecord;
import akka.javasdk.ledger.LedgerClient;
import io.akka.evalkit.domain.Scorer;
import io.akka.evalkit.metric.AlignmentMetric;

import java.util.Objects;

/**
 * Runs an {@link AlignmentMetric} against a recorded interaction.
 *
 * <p>{@code StepEfficiency}, {@code PlanQuality}, {@code PlanAdherence} and
 * {@code TaskCompletion} score by one model call and already satisfy {@link Scorer}, so the
 * metric is the scorer here.
 *
 * <p>The assessor the metric was built with is what reaches a model. A subclass carrying a
 * {@code @Component} id supplies one that calls the judge agent the binding project runs.
 *
 * <p>{@code PlanQuality} and {@code PlanAdherence} read the plan from
 * {@link akka.javasdk.ledger.ModelResponse#thinking()}. A provider with reasoning switched
 * off records none, and both metrics return {@code Unscoreable} rather than scoring a run
 * that surfaced no plan.
 */
public abstract class AlignmentEvaluator extends ScorerEvaluator {

    private final AlignmentMetric metric;

    protected AlignmentEvaluator(LedgerClient ledger, AlignmentMetric metric) {
        super(ledger);
        this.metric = Objects.requireNonNull(metric, "metric");
    }

    @Override
    protected final Scorer scorer() {
        return metric;
    }

    /**
     * What the interaction was meant to achieve.
     *
     * <p>{@code StepEfficiency} and {@code TaskCompletion} read this as the task. A record
     * carries no statement of intent, so the default is the text the user sent, which is the
     * nearest thing an interaction holds to a task.
     */
    @Override
    protected String expectedOutcome(InteractionRecord record) {
        return record.inputText();
    }
}
