package io.akka.evalkit.metric;

import io.akka.evalkit.domain.Recording;

import java.util.Optional;
import java.util.function.Function;

/**
 * Whether the agent then did what it had planned to do.
 *
 * <p>Needs both halves: a plan, which comes from the reasoning the run's model calls carried
 * or from {@link #readingPlanFrom}, and the steps, which are the calls the run made. A run
 * missing either is {@link io.akka.evalkit.domain.RunOutcome.Unscoreable}, for the reason
 * {@link PlanQuality} states.
 *
 * <p>Ported in shape from DeepEval's {@code PlanAdherenceMetric}, Apache 2.0. See
 * {@link AlignmentMetric} for what a model-scored metric costs and what upstream pins.
 */
public final class PlanAdherence extends AlignmentMetric {

    private final Function<Recording, Optional<String>> plans;

    public PlanAdherence(Assessor assessor) {
        this(new MetricRef("plan-adherence", 1), 0.5, assessor, AlignmentMetric::recordedPlan);
    }

    private PlanAdherence(MetricRef ref, double threshold, Assessor assessor,
                          Function<Recording, Optional<String>> plans) {
        super(ref, threshold, assessor);
        this.plans = plans;
    }

    /** Where the agent's plan comes from, replacing the recorded reasoning. */
    public PlanAdherence readingPlanFrom(Function<Recording, Optional<String>> source) {
        return new PlanAdherence(ref(), threshold(), assessor(), source);
    }

    /** The score a run must reach. Changing it changes the version. */
    public PlanAdherence scoringAtLeast(double threshold) {
        return new PlanAdherence(new MetricRef(ref().metricId(), ref().version() + 1),
            threshold, assessor(), plans);
    }

    @Override
    protected Optional<Question> ask(Recording recording) {
        String task = recording.transcript().expectedOutcome();
        String steps = StepEfficiency.render(recording);
        if (task.isBlank() || steps.isEmpty()) return Optional.empty();

        return plans.apply(recording)
            .filter(plan -> !plan.isBlank())
            .map(plan -> new Question("task and plan against steps",
                task + "\n\nThe plan the agent formed:\n" + plan, steps));
    }

    @Override
    protected String absence() {
        return "the run recorded no plan or no steps, so there is nothing to hold the one against the other";
    }
}
