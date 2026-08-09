package io.akka.evalkit.domain;

import java.util.List;
import java.util.Map;

/**
 * How the system under test is put into the state a scenario assumes.
 *
 * <p>A scenario testing a conversation's first turn is the exception. Of the 2,365
 * scenarios in the reference corpus, 502 carry recorded setup, and the claim corpora run
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

    /** The turns to send before the graded one, in order. */
    static Precursor replay(String... userTurns) {
        return new Replay(List.of(userTurns));
    }

    /** Whether reaching this state exercises the target's own path to it. */
    default boolean provesReachability() {
        return this instanceof Replay;
    }

    default String describe() {
        return switch (this) {
            case None ignored -> "none";
            case Replay r -> "replay of " + r.userTurns().size() + " turns";
            case Fixture f -> "fixture " + f.name();
        };
    }
}
