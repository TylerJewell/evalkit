package io.akka.evalkit.domain;

/**
 * Why a run ended without producing an answer.
 *
 * <p>A run a guardrail stopped, a run that hit a rate limit and a run whose model returned
 * something unparseable all look identical from a score: no answer. They are not the same
 * finding. The first is the system working, the second is a quota, and the third is a defect.
 * A harness that reports all three as a low score reports a working system as a broken one,
 * which is the mistake {@link RunOutcome} exists to prevent.
 *
 * <p>The reasons track what a recorded interaction carries, so a failure this kit observed
 * and a failure read back from an interaction log are the same value.
 *
 * @param reason      the category, decided where the failure happened
 * @param description what the runtime said, for the row a reader acts on
 */
public record Failure(Reason reason, String description) {

    /** Why an interaction terminated without an answer. */
    public enum Reason {
        /** The provider rejected or errored on the call. */
        MODEL,
        /** The provider refused for quota reasons. */
        RATE_LIMIT,
        /** Nothing came back in time. */
        TIMEOUT,
        /** The call asked for something the provider does not support. */
        UNSUPPORTED_FEATURE,
        /** The runtime failed on its own account. */
        INTERNAL,
        /** Something came back and could not be read as the shape that was asked for. */
        OUTPUT_PARSING,
        /** A tool the agent called failed. */
        TOOL_CALL,
        /** A tool reached over MCP failed. */
        MCP_TOOL_CALL,
        /** A guardrail stopped the interaction, which is the system working. */
        GUARDRAIL,
        /** The runtime did not say. */
        UNSPECIFIED
    }

    public Failure {
        if (reason == null) reason = Reason.UNSPECIFIED;
        description = description == null ? "" : description;
    }

    public static Failure of(Reason reason, String description) {
        return new Failure(reason, description);
    }

    /** The row a reader sees. */
    public String describe() {
        return description.isEmpty() ? reason.toString() : reason + " — " + description;
    }
}
