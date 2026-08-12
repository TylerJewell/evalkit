package io.akka.evalkit.metric;

import io.akka.evalkit.domain.Observation;

import java.util.Optional;
import java.util.function.Function;

/**
 * Whether the plan the agent formed would achieve the task.
 *
 * <p>The plan comes from the reasoning the run's model calls carried, which
 * {@link io.akka.evalkit.domain.ModelCall#thinking()} records. A target that surfaces its
 * plan some other way supplies it through {@link #readingPlanFrom}, and a run whose calls
 * reported no reasoning produces {@link io.akka.evalkit.domain.RunOutcome.Inconclusive}
 * rather than a score.
 *
 * <p><b>The divergence worth knowing about.</b> Upstream scores 1 and passes when it finds
 * no plan in a trace, and its own documentation observes that a perfect score there usually
 * means the reasoning was never surfaced. A campaign of those reports an agent that reveals
 * nothing as an agent that plans perfectly, so this reports the absence instead.
 *
 * <p>Ported in shape from DeepEval's {@code PlanQualityMetric}, Apache 2.0. See
 * {@link AlignmentMetric} for what a model-scored metric costs and what upstream pins.
 */
public final class PlanQuality extends AlignmentMetric {

    private final Function<Observation, Optional<String>> plans;

    public PlanQuality(Assessor assessor) {
        this(new MetricRef("plan-quality", 1), 0.5, assessor, AlignmentMetric::recordedPlan);
    }

    private PlanQuality(MetricRef ref, double threshold, Assessor assessor,
                        Function<Observation, Optional<String>> plans) {
        super(ref, threshold, assessor);
        this.plans = plans;
    }

    /** Where the agent's plan comes from, replacing the recorded reasoning. */
    public PlanQuality readingPlanFrom(Function<Observation, Optional<String>> source) {
        return new PlanQuality(ref(), threshold(), assessor(), source);
    }

    /** The score a run must reach. Changing it changes the version. */
    public PlanQuality scoringAtLeast(double threshold) {
        return new PlanQuality(new MetricRef(ref().metricId(), ref().version() + 1),
            threshold, assessor(), plans);
    }

    @Override
    protected Optional<Question> ask(Observation observation) {
        String task = observation.transcript().expectedOutcome();
        if (task.isBlank()) return Optional.empty();
        return plans.apply(observation)
            .filter(plan -> !plan.isBlank())
            .map(plan -> new Question("task against plan", task, plan));
    }

    @Override
    protected String absence() {
        return "the run recorded no plan, and a run that reveals no plan is not a run that planned well";
    }
}
