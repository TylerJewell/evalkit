package io.akka.evalkit.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Whether the system landed on the node a scenario expected.
 *
 * <p>Not string equality, and not fuzzy either. Node ids are hierarchical: a scenario may
 * cite {@code GenUC-16a} where the system reports the more specific {@code GenUC-16a.3},
 * and arriving somewhere more precise than asked is arriving in the right place. The
 * reverse is not true &mdash; a scenario expecting {@code GenUC-16a.3} is not satisfied by
 * a system that got as far as {@code GenUC-16a} and stopped.
 *
 * <p>Everything beyond that is a mismatch and is reported with both ids, because a
 * tolerant comparison here would hide exactly the drift this is meant to catch.
 */
public final class SpecNodeMatch {

    private SpecNodeMatch() {}

    public static RunOutcome assertReached(String expected, Optional<String> actual) {
        String reached = actual.map(String::trim).orElse("");
        if (reached.isEmpty()) {
            // The target does not report nodes, or reported none for this turn. Silently
            // passing would make every assertion vacuous; silently failing would blame
            // the system for the harness not being wired.
            return new RunOutcome.Inconclusive(
                "expected node " + expected + " but the target reported none — "
                    + "does this target track specification nodes?");
        }
        return new RunOutcome.Asserted(matches(expected, reached), expected, reached);
    }

    /** {@code true} when {@code actual} is the expected node, or a refinement of it. */
    public static boolean matches(String expected, String actual) {
        String want = normalise(expected);
        String got = normalise(actual);
        if (want.equals(got)) return true;
        // A refinement continues at a boundary: GenUC-16a.3 refines GenUC-16a; GenUC-16ab
        // does not refine GenUC-16a.
        return got.startsWith(want) && !Character.isLetterOrDigit(got.charAt(want.length()));
    }

    private static String normalise(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }
}
