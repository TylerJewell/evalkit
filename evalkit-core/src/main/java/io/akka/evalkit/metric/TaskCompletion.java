package io.akka.evalkit.metric;

import io.akka.evalkit.domain.Recording;

import java.util.Optional;

/**
 * Whether the agent did what it was asked to do.
 *
 * <p>Reads the task the scenario stated against what the agent produced. Referenceless
 * upstream, where the task is inferred from a trace; here the task is the scenario's own
 * expected outcome, which is stated data rather than something a harness worked out for
 * itself &mdash; the distinction {@code docs/design-history.md} records under scores that
 * measured the harness.
 *
 * <p>Ported in shape from DeepEval's {@code TaskCompletionMetric}, Apache 2.0. See
 * {@link AlignmentMetric} for what a model-scored metric costs and what upstream pins.
 */
public final class TaskCompletion extends AlignmentMetric {

    public TaskCompletion(Assessor assessor) {
        this(new MetricRef("task-completion", 1), 0.5, assessor);
    }

    private TaskCompletion(MetricRef ref, double threshold, Assessor assessor) {
        super(ref, threshold, assessor);
    }

    /** The score a run must reach. Changing it changes the version. */
    public TaskCompletion scoringAtLeast(double threshold) {
        return new TaskCompletion(new MetricRef(ref().metricId(), ref().version() + 1),
            threshold, assessor());
    }

    @Override
    protected Optional<Question> ask(Recording recording) {
        String task = recording.transcript().expectedOutcome();
        String outcome = outcomeOf(recording);
        return task.isBlank() || outcome.isBlank() ? Optional.empty()
            : Optional.of(new Question("task against outcome", task, outcome));
    }

    @Override
    protected String absence() {
        return "the run recorded neither a task nor an outcome to read it against";
    }
}
