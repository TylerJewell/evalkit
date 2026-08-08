package io.akka.evalkit.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * What a run produced beyond the text a rubric reads.
 *
 * <p>Kept apart from {@link Transcript} because that record holds exactly the four fields
 * a rubric interpolates, and adding a field there would change what every judge sees.
 * A metric reads this; a judge does not.
 *
 * <p>Every field is optional or empty by default, because a target outside Akka reports
 * what it can and the report says which figures it could not measure.
 *
 * @param node        the specification node the answer came from, when the target tracks one
 * @param latency     how long the graded turn took, when the target timed it
 * @param toolsCalled the tools invoked while answering, in the order they were called
 * @param tokens      what the graded turn spent, when the target can see its own usage
 */
public record Evidence(Optional<String> node, Optional<Duration> latency,
                       List<ToolCall> toolsCalled, Tokens tokens) {

    public static final Evidence NONE =
        new Evidence(Optional.empty(), Optional.empty(), List.of(), Tokens.NONE);

    public Evidence {
        node = node == null ? Optional.empty() : node;
        latency = latency == null ? Optional.empty() : latency;
        toolsCalled = toolsCalled == null ? List.of() : List.copyOf(toolsCalled);
        tokens = tokens == null ? Tokens.NONE : tokens;
    }

    /** The names of the tools invoked, for a scorer comparing against a policy. */
    public List<String> toolNames() {
        return toolsCalled.stream().map(ToolCall::name).toList();
    }
}
