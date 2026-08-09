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
 * <p>Shaped to match a recorded interaction, so that a run this kit executed and a run read
 * back from an interaction log carry the same evidence and score on the same metrics.
 *
 * @param node          the specification node the answer came from, when the target tracks one
 * @param latency       how long the graded turn took, when the target timed it
 * @param toolsCalled   the tools invoked while answering, in the order they were called
 * @param modelCalls    the calls the run made to a model, in order. Empty for a target that
 *                      reports only its final answer, which is why every metric reading them
 *                      has to handle their absence
 * @param systemMessage the instruction the model was given, when the target reports it
 * @param failure       what ended the run without an answer, when something did
 * @param tokens        what the graded turn spent, when the target can see its own usage
 */
public record Evidence(Optional<String> node, Optional<Duration> latency,
                       List<ToolCall> toolsCalled, List<ModelCall> modelCalls,
                       String systemMessage, Optional<Failure> failure, Tokens tokens) {

    public static final Evidence NONE =
        new Evidence(Optional.empty(), Optional.empty(), List.of(), List.of(), "",
            Optional.empty(), Tokens.NONE);

    public Evidence {
        node = node == null ? Optional.empty() : node;
        latency = latency == null ? Optional.empty() : latency;
        toolsCalled = toolsCalled == null ? List.of() : List.copyOf(toolsCalled);
        modelCalls = modelCalls == null ? List.of() : List.copyOf(modelCalls);
        systemMessage = systemMessage == null ? "" : systemMessage;
        failure = failure == null ? Optional.empty() : failure;
        tokens = tokens == null ? Tokens.NONE : tokens;
    }

    /** Evidence from a target that reports its answer, its node and its tool calls. */
    public Evidence(Optional<String> node, Optional<Duration> latency,
                    List<ToolCall> toolsCalled, Tokens tokens) {
        this(node, latency, toolsCalled, List.of(), "", Optional.empty(), tokens);
    }

    /** The names of the tools invoked, for a scorer comparing against a policy. */
    public List<String> toolNames() {
        return toolsCalled.stream().map(ToolCall::name).toList();
    }

    /** The same evidence, with the model calls the run made. */
    public Evidence over(List<ModelCall> calls) {
        return new Evidence(node, latency, toolsCalled, calls, systemMessage, failure, tokens);
    }

    /** The same evidence, with what ended the run. */
    public Evidence failing(Failure what) {
        return new Evidence(node, latency, toolsCalled, modelCalls, systemMessage,
            Optional.ofNullable(what), tokens);
    }

    /**
     * The reasoning the run surfaced, in the order the calls were made.
     *
     * <p>Empty when no call reported any, which is the ordinary case for a provider with
     * reasoning switched off. A metric about planning treats that as an absence rather than
     * as an agent that did not plan &mdash; see {@link io.akka.evalkit.metric.PlanQuality}.
     */
    public String thinking() {
        return modelCalls.stream()
            .map(ModelCall::thinking)
            .filter(reasoning -> !reasoning.isEmpty())
            .reduce((first, second) -> first + "\n" + second)
            .orElse("");
    }

    /**
     * Model calls that produced text and reported no usage.
     *
     * <p>Counted rather than assumed, so a token total can be labelled a floor on evidence.
     * A target reporting no model calls at all counts none, because nothing was claimed
     * either way.
     */
    public int callsMissingUsage() {
        return (int) modelCalls.stream()
            .filter(call -> !call.content().isEmpty() && !call.reportedUsage())
            .count();
    }

    /**
     * What the recorded calls spent, when they reported it.
     *
     * <p>Falls back to {@link #tokens} for a target that accounts for a whole run rather
     * than per call, so neither way of reporting is privileged.
     */
    public Tokens spend() {
        if (modelCalls.isEmpty()) return tokens;
        return modelCalls.stream().map(ModelCall::tokens).reduce(Tokens.NONE, Tokens::plus);
    }
}
