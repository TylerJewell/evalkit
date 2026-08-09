package io.akka.evalkit.domain;

/**
 * One call a run made to a model, and what came back.
 *
 * <p>A single graded turn is often several model calls: the agent reasons, calls a tool,
 * reads the result, reasons again. Recording only the last of those loses the work, and the
 * work is what a metric about plans or about efficiency is asking to see.
 *
 * <p><b>{@code thinking} is why this type exists.</b> A metric that judges whether an agent
 * planned well needs the plan, and an agent's plan is not in its reply &mdash; it is in the
 * reasoning the provider returned alongside it. {@link io.akka.evalkit.metric.PlanQuality}
 * and {@link io.akka.evalkit.metric.PlanAdherence} read it here.
 *
 * <p>Shaped to match what a recorded interaction carries per model call, so a run this kit
 * executed and a run read back from an interaction log score on the same evidence.
 *
 * @param id           the provider's id for the call, empty when it reported none
 * @param content      the text the model produced, empty for a call that only invoked tools
 * @param thinking     the reasoning the provider returned, empty when it returned none.
 *                     Empty is the ordinary case for a provider with reasoning switched off,
 *                     and it is not the same as an agent that did not plan
 * @param inputTokens  tokens in the prompt for this call, 0 when the provider reported none
 * @param outputTokens tokens this call generated, 0 when the provider reported none
 */
public record ModelCall(String id, String content, String thinking,
                        long inputTokens, long outputTokens) {

    public ModelCall {
        id = id == null ? "" : id;
        content = content == null ? "" : content;
        thinking = thinking == null ? "" : thinking;
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts cannot be negative");
        }
    }

    /** A call carrying only what the model said. */
    public static ModelCall of(String content) {
        return new ModelCall("", content, "", 0, 0);
    }

    /** The same call, with the reasoning the provider returned alongside the answer. */
    public ModelCall thinking(String reasoning) {
        return new ModelCall(id, content, reasoning, inputTokens, outputTokens);
    }

    /** The same call, with what it spent. */
    public ModelCall costing(long in, long out) {
        return new ModelCall(id, content, thinking, in, out);
    }

    public boolean statesThinking() {
        return !thinking.isEmpty();
    }

    /**
     * Whether this call reported what it spent.
     *
     * <p>A call that produced text and reported no tokens was not free; it was unmeasured.
     * The report counts these so a total can be labelled a floor rather than presented as a
     * measurement.
     */
    public boolean reportedUsage() {
        return inputTokens > 0 || outputTokens > 0;
    }

    public Tokens tokens() {
        return new Tokens(inputTokens, outputTokens);
    }
}
