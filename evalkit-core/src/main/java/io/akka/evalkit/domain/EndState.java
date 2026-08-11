package io.akka.evalkit.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Whether the system left the world in the state the scenario expected.
 *
 * <p>An agent that says the right thing and does the wrong thing passes every check that
 * reads only the reply. This one reads what changed: the refund was issued, the booking
 * moved, the ticket was raised.
 *
 * <p><b>Any route that arrives is a correct route.</b> The expected state names what must
 * be true at the end, never the calls that must have produced it. A scenario that pinned
 * the sequence would fail an agent that reached the same place by a better path, and
 * turn every refactor of the service into a dataset rewrite. Where the sequence genuinely
 * is the requirement, {@link io.akka.evalkit.metric.ToolCorrectness} is the metric that
 * says so.
 *
 * <p>Only the named keys are compared. A target reports whatever state it can see, and
 * most of it has nothing to do with the scenario; requiring an exact match would make
 * every scenario fail the first time the target reported one extra field.
 */
public final class EndState implements Scorer {

    private final Map<String, String> expected;

    private EndState(Map<String, String> expected) {
        this.expected = Map.copyOf(expected);
    }

    /** @throws IllegalArgumentException when nothing is expected, which would pass anything */
    public static EndState matching(Map<String, String> expected) {
        if (expected == null || expected.isEmpty()) {
            throw new IllegalArgumentException("no expected state given");
        }
        expected.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("an expected state key is blank");
            }
        });
        return new EndState(expected);
    }

    public static EndState matching(String key, String value) {
        return matching(Map.of(key, value));
    }

    public Map<String, String> expected() {
        return expected;
    }

    @Override
    public RunOutcome score(Recording recording) {
        var actual = recording.endState();
        if (actual.isEmpty()) {
            // The target does not report state, so nothing here is a finding about the
            // system. Failing would blame it for evidence the harness never received.
            return new RunOutcome.Unscoreable(
                "the target reported no state, so the end state cannot be compared — "
                    + "does this target expose what a run changed?");
        }
        var wrong = new TreeMap<String, String>();
        for (var entry : expected.entrySet()) {
            String found = actual.get(entry.getKey());
            if (!entry.getValue().equals(found)) {
                wrong.put(entry.getKey(), found == null ? "nothing" : found);
            }
        }
        if (wrong.isEmpty()) {
            return new RunOutcome.Asserted(true, describe(expected), "the same");
        }
        var wanted = new LinkedHashMap<String, String>();
        wrong.keySet().forEach(key -> wanted.put(key, expected.get(key)));
        return new RunOutcome.Asserted(false, describe(wanted), describe(wrong));
    }

    @Override
    public String id() {
        return "end-state";
    }

    private static String describe(Map<String, String> state) {
        return state.entrySet().stream()
            .map(e -> e.getKey() + " " + e.getValue())
            .reduce((a, b) -> a + ", " + b)
            .orElse("nothing");
    }
}
