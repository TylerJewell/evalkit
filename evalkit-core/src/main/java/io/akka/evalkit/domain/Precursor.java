package io.akka.evalkit.domain;

import java.util.List;
import java.util.Map;

/**
 * How the system under test is put into the state a scenario assumes.
 *
 * <p>Most scenarios do not test a conversation's first turn. Of 2,365 Reference scenarios,
 * 502 carry recorded setup, and for the claim corpora the full conversation runs to a
 * median of 22 messages against a single graded exchange &mdash; every one of those setup
 * turns costing model calls, latency, and a chance to fail before the interesting part.
 *
 * <p><b>This is where an Akka-native harness stops being a Reference clone.</b> Reference is
 * outside the system and has to come through the front door. A harness that knows the
 * target is an Akka service does not: the state those 21 messages exist to produce is a
 * record in an entity, and it can be put there directly.
 */
public sealed interface Precursor {

    /** The scenario starts from nothing. */
    record None() implements Precursor {}

    /**
     * Walk the recorded turns through the front door.
     *
     * <p>Slow and able to fail, and still necessary. A suite that only ever seeds stops
     * proving the states are <em>reachable</em> &mdash; the same blind spot that let two
     * complete workflows sit in a prior service with no caller while their tests passed. Seeded runs
     * verify the graded turn; only walked runs verify the path to it.
     */
    record Replay(List<String> userTurns) implements Precursor {
        public Replay {
            userTurns = List.copyOf(userTurns);
        }
    }

    /**
     * Ask the target for a state it knows how to build, by name.
     *
     * <p>A named fixture rather than a list of events on purpose. Events would put the
     * target's event types in evalkit's domain and make this harness specific to one
     * service; a name keeps the seam at "the target knows how to reach the state it owns",
     * which is the only side that could know.
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
