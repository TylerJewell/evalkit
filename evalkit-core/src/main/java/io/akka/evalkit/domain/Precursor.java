package io.akka.evalkit.domain;

import java.util.List;
import java.util.Map;

/**
 * How the system under test is put into the state a scenario assumes.
 *
 * <p>A scenario testing a conversation's first turn is the exception. Of the 2,365
 * scenarios in the reference dataset, 502 carry recorded setup, and the claim datasets run
 * to a median of 22 messages before the graded exchange. Every setup turn costs a model
 * call, adds latency, and can fail before the graded turn is reached.
 *
 * <p>{@link Replay} sends those turns through the interface a user would use. {@link
 * Fixture} asks the target to write the state directly, which costs no model calls.
 */
public sealed interface Precursor {

    /** The scenario starts from nothing. */
    record None() implements Precursor {}

    /**
     * Walk the recorded turns through the front door.
     *
     * <p>Walking the turns is slow and can fail, and it is the only evidence that the
     * state is reachable. A suite that only seeds states verifies the graded turn and
     * never verifies the path to it, which is how an unreachable state keeps passing.
     */
    record Replay(List<String> userTurns) implements Precursor {
        public Replay {
            userTurns = List.copyOf(userTurns);
        }
    }

    /**
     * Ask the target for a state it knows how to build, by name.
     *
     * <p>The target is asked for a name, never for a list of events. Events would put
     * the target's event types into evalkit's domain and tie this harness to one service.
     * The target is the only side that knows how to reach the state it owns.
     */
    record Fixture(String name, Map<String, String> parameters) implements Precursor {
        public Fixture {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("fixture name required");
            }
            parameters = Map.copyOf(parameters);
        }

        public static Fixture named(String name) {
            return new Fixture(name, Map.of());
        }
    }

    /**
     * Put the system in a state where a named tool fails, and see what it does about it.
     *
     * <p>Tools fail in production, and what an agent does then is the difference between a
     * service that says it cannot check right now and one that invents an answer. A
     * campaign that never breaks a tool has no evidence either way, because the tools it
     * called all worked.
     *
     * <p>The target is asked for a tool by name and decides how to break it, for the reason
     * {@link Fixture} asks for a state by name: the target owns the tool and is the only
     * side that knows how to make it fail the way it fails.
     *
     * @param message what the tool fails with, so a scenario can expect the agent to
     *                repeat it rather than paraphrase it
     */
    record FailingTool(String tool, String message, Precursor then) implements Precursor {
        public FailingTool {
            if (tool == null || tool.isBlank()) {
                throw new IllegalArgumentException("tool name required");
            }
            message = message == null || message.isBlank() ? "the tool failed" : message;
            then = then == null ? new None() : then;
        }

        public static FailingTool named(String tool) {
            return new FailingTool(tool, "the tool failed", new None());
        }

        /** The same broken tool, reached from a state the target builds first. */
        public FailingTool after(Precursor precursor) {
            return new FailingTool(tool, message, precursor);
        }
    }

    /** The turns to send before the graded one, in order. */
    static Precursor replay(String... userTurns) {
        return new Replay(List.of(userTurns));
    }

    /** Whether reaching this state exercises the target's own path to it. */
    default boolean provesReachability() {
        return this instanceof Replay
            || (this instanceof FailingTool broken && broken.then().provesReachability());
    }

    /** The tools this precursor asks the target to break, which a target may not be able to. */
    default java.util.Set<String> brokenTools() {
        return this instanceof FailingTool broken
            ? java.util.stream.Stream.concat(java.util.stream.Stream.of(broken.tool()),
                broken.then().brokenTools().stream()).collect(java.util.stream.Collectors.toSet())
            : java.util.Set.of();
    }

    default String describe() {
        return switch (this) {
            case None ignored -> "none";
            case Replay r -> "replay of " + r.userTurns().size() + " turns";
            case Fixture f -> "fixture " + f.name();
            case FailingTool broken -> "tool " + broken.tool() + " failing"
                + (broken.then() instanceof None ? "" : ", after " + broken.then().describe());
        };
    }
}
