package io.akka.evalkit.domain;

import akka.javasdk.ledger.Failure;
import akka.javasdk.ledger.ModelResponse;
import akka.javasdk.ledger.ToolCall;

import java.util.Map;

/**
 * The service being evaluated, as the harness needs to see it.
 *
 * <p>Two operations, because that is all evaluation requires: put the system in a state,
 * then say something to it. Everything specific &mdash; whether the state arrives by
 * walking a conversation or by seeding an entity, whether the turn goes over HTTP or
 * through a {@code ComponentClient} &mdash; is the adapter's business.
 *
 * <p><b>Fixtures are declared, not discovered.</b> {@link #fixtures()} exists so a
 * campaign can be refused before it runs rather than producing several hundred
 * {@link RunOutcome.NotReached} results that all mean "that fixture was never
 * implemented". A campaign that fails at minute forty for a reason knowable at minute
 * zero is a campaign that wasted forty minutes.
 */
public interface SystemUnderTest {

    /** The result of getting the system into position. */
    sealed interface Prepared {

        /**
         * @param sessionId    what {@link #submit} should address
         * @param historySoFar the conversation the precursor produced, for the judge's
         *                     {@code replay_history}. Empty for a seeded state, which is
         *                     honest: nothing was said, so there is nothing to show
         */
        record Ready(String sessionId, String historySoFar) implements Prepared {}

        record Failed(String reason) implements Prepared {}
    }

    /**
     * Put the system into the state a scenario assumes.
     *
     * <p>Must not throw for an expected failure. A precursor that cannot land is
     * {@link Prepared.Failed} and becomes {@link RunOutcome.NotReached} &mdash; a fact
     * about the harness, not a verdict on the system.
     */
    Prepared prepare(Precursor precursor);

    /**
     * What the system said, and where it said it from.
     *
     * @param node the specification node the answer came from, when the target tracks
     *             one. This is what lets a scenario naming a decision be settled by
     *             comparison instead of by a model — the difference between a tick and
     *             a paid, slightly random opinion
     */
    record Reply(String text, java.util.Optional<String> node,
                 java.util.Optional<java.time.Duration> latency,
                 java.util.List<ToolCall> toolsCalled,
                 java.util.List<ModelResponse> modelCalls,
                 String systemMessage,
                 java.util.Optional<Failure> failure) {

        public Reply {
            node = node == null ? java.util.Optional.empty() : node;
            latency = latency == null ? java.util.Optional.empty() : latency;
            toolsCalled = toolsCalled == null ? java.util.List.of()
                : java.util.List.copyOf(toolsCalled);
            modelCalls = modelCalls == null ? java.util.List.of()
                : java.util.List.copyOf(modelCalls);
            systemMessage = systemMessage == null ? "" : systemMessage;
            failure = failure == null ? java.util.Optional.empty() : failure;
        }

        /** A reply carrying what the system said and where it said it from. */
        public Reply(String text, java.util.Optional<String> node,
                     java.util.Optional<java.time.Duration> latency,
                     java.util.List<ToolCall> toolsCalled) {
            this(text, node, latency, toolsCalled, java.util.List.of(), "",
                java.util.Optional.empty());
        }

        /** A reply carrying only what the system said. */
        public Reply(String text, java.util.Optional<String> node) {
            this(text, node, java.util.Optional.empty(), java.util.List.of());
        }

        public static Reply of(String text) {
            return new Reply(text, java.util.Optional.empty());
        }

        public static Reply from(String text, String node) {
            return new Reply(text, java.util.Optional.ofNullable(node));
        }

        /** The same reply, with how long it took. */
        public Reply taking(java.time.Duration elapsed) {
            return new Reply(text, node, java.util.Optional.ofNullable(elapsed), toolsCalled,
                modelCalls, systemMessage, failure);
        }

        /** The same reply, with the tools the system invoked while answering. */
        public Reply calling(ToolCall... tools) {
            return new Reply(text, node, latency, java.util.List.of(tools), modelCalls,
                systemMessage, failure);
        }

        /**
         * The same reply, with the model calls behind it.
         *
         * <p>A target that can report its own calls reports them here, and the reasoning
         * they carried is what a metric about planning reads. A target that reports only its
         * final answer leaves this empty, which those metrics treat as an absence.
         */
        public Reply over(ModelResponse... calls) {
            return new Reply(text, node, latency, toolsCalled, java.util.List.of(calls),
                systemMessage, failure);
        }

        /** The same reply, with the instruction the model was given. */
        public Reply instructedBy(String instruction) {
            return new Reply(text, node, latency, toolsCalled, modelCalls, instruction, failure);
        }
    }

    /** Say the graded turn, and return what the system said back. */
    Reply submit(String sessionId, String userText);

    /**
     * States this target can construct, each with a one-line description.
     *
     * <p>The description is written where the state is built, because that is the only
     * code that knows what "authenticated with a claim open" contains. A run summary has
     * to explain the starting points to a reader who does not know the system, and the
     * harness cannot supply those words itself.
     */
    Map<String, String> fixtures();

    /**
     * Tokens the system under test spent answering, when it can account for them.
     *
     * <p>Asked once after the run rather than per turn: a target usually learns its own
     * usage from a session or an interaction log, which is cheaper to read whole than to
     * sample between scenarios.
     *
     * <p>Defaults to nothing spent. A target that cannot see its own model calls should
     * leave it, and the report will say the figure is a floor rather than quietly
     * present zero as a measurement.
     */
    /**
     * Specification nodes this target can emit, when it knows them.
     *
     * <p>An assertion against a node the target cannot produce fails for every input on
     * every run, which reads as a defect in the system and is a defect in the campaign.
     * Declaring the set lets that be refused before the run instead of discovered after
     * it &mdash; the same argument as {@link #fixtures()}.
     *
     * <p>Empty means "unknown", not "none": a target that cannot enumerate its nodes is
     * not checked rather than being refused outright.
     */
    /**
     * Tools this target can be asked to break, for scenarios that test recovery.
     *
     * <p>Empty means the target cannot break anything, and a campaign asking it to is
     * refused before it runs. Silently answering with working tools would report the
     * system as recovering from a failure that never happened.
     */
    default java.util.Set<String> breakableTools() {
        return java.util.Set.of();
    }

    default java.util.Set<String> emittableNodes() {
        return java.util.Set.of();
    }

    default Accounting spend() {
        return new Accounting(Tokens.NONE, 1);
    }

    /**
     * @param tokens what was measured
     * @param callsMissingUsage replies whose usage the target could not see, so that an
     *                          unaccounted call is visible rather than absent
     */
    record Accounting(Tokens tokens, int callsMissingUsage) {}
}
