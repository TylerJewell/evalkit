package io.akka.evalkit.metric;

import akka.javasdk.ledger.ModelResponse;
import akka.javasdk.ledger.ToolCall;
import io.akka.evalkit.domain.Observation;
import io.akka.evalkit.ledger.Arguments;

import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Whether the agent reached the task without steps it did not need.
 *
 * <p>The steps are what the run did: the calls it made to a model and the tools it invoked.
 * A run that recorded neither has nothing to call efficient or wasteful.
 *
 * <p><b>The two sequences are rendered in order within their kind, and not interleaved.</b>
 * A observation keeps model calls and tool calls as separate ordered lists, so the exact
 * alternation between them is not recoverable. An assessor is told which steps happened and
 * in what order each kind happened; it is not told that a particular tool call fell between
 * two particular model calls.
 *
 * <p>Ported in shape from DeepEval's {@code StepEfficiencyMetric}, Apache 2.0. See
 * {@link AlignmentMetric} for what a model-scored metric costs and what upstream pins.
 */
public final class StepEfficiency extends AlignmentMetric {

    public StepEfficiency(Assessor assessor) {
        this(new MetricRef("step-efficiency", 1), 0.5, assessor);
    }

    private StepEfficiency(MetricRef ref, double threshold, Assessor assessor) {
        super(ref, threshold, assessor);
    }

    /** The score a run must reach. Changing it changes the version. */
    public StepEfficiency scoringAtLeast(double threshold) {
        return new StepEfficiency(new MetricRef(ref().metricId(), ref().version() + 1),
            threshold, assessor());
    }

    @Override
    protected Optional<Question> ask(Observation observation) {
        String task = observation.transcript().expectedOutcome();
        String steps = render(observation);
        return task.isBlank() || steps.isEmpty() ? Optional.empty()
            : Optional.of(new Question("task against steps", task, steps));
    }

    @Override
    protected String absence() {
        return "the run recorded no steps, so there is no work to call efficient or wasteful";
    }

    /**
     * The steps as the assessor reads them, one per line.
     *
     * <p>Model calls first and tool calls after, each in the order the run made them. See the
     * note on this class about what that ordering does and does not establish.
     */
    static String render(Observation observation) {
        return Stream.concat(
                observation.modelCalls().stream().map(StepEfficiency::describe),
                observation.toolsCalled().stream().map(StepEfficiency::describe))
            .collect(Collectors.joining("\n"));
    }

    private static String describe(ModelResponse call) {
        return call.thinking() != null && !call.thinking().isEmpty()
            ? "model call, reasoning: " + call.thinking()
            : "model call";
    }

    private static String describe(ToolCall call) {
        var arguments = Arguments.read(call.arguments()).orElse(java.util.Map.of());
        return arguments.isEmpty() ? "tool: " + call.name()
            : "tool: " + call.name() + " " + new java.util.TreeMap<>(arguments);
    }
}
