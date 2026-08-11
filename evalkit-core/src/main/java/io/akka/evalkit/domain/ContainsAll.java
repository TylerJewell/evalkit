package io.akka.evalkit.domain;

import java.util.List;
import java.util.Locale;

/**
 * Whether the reply stated everything the scenario requires it to state.
 *
 * <p>A refusal is only a correct refusal if it says what the caller may do instead, and a
 * policy answer is only correct if it names the term the policy turns on. Both are
 * decisions with a right answer, so this compares rather than judges: no model call, no
 * variance between runs, and a finding a reader can act on without consulting a rubric.
 *
 * <p>Only the reply is searched. The graded exchange also carries what the user said, and
 * a phrase the user supplied would satisfy a search over it while the system never said
 * the thing at all &mdash; a pass that measures the script.
 *
 * <p>Case-insensitive and whitespace-tolerant, and deliberately nothing more. A comparison
 * that tolerated paraphrase would pass replies that omit the term, which is what this
 * exists to catch. A requirement that genuinely admits paraphrase belongs to a judge.
 */
public final class ContainsAll implements Scorer {

    private final List<String> phrases;

    private ContainsAll(List<String> phrases) {
        this.phrases = List.copyOf(phrases);
    }

    /** @throws IllegalArgumentException when given nothing to look for */
    public static ContainsAll of(List<String> phrases) {
        if (phrases == null || phrases.isEmpty()) {
            // A scorer with no phrases passes every reply, which reads in the report as a
            // requirement that was checked.
            throw new IllegalArgumentException("no required phrases given");
        }
        phrases.forEach(p -> {
            if (p == null || p.isBlank()) {
                throw new IllegalArgumentException("a required phrase is blank");
            }
        });
        return new ContainsAll(phrases);
    }

    public static ContainsAll of(String... phrases) {
        return of(List.of(phrases));
    }

    public List<String> phrases() {
        return phrases;
    }

    @Override
    public RunOutcome score(Recording recording) {
        String reply = recording.transcript().systemOutput();
        if (reply.isBlank()) {
            // The target does not report reply text, or reported none for this turn.
            // Failing would blame the system for evidence the harness never received.
            return new RunOutcome.Unscoreable(
                "no reply text to search for the required phrases — "
                    + "does this target report what the system said?");
        }
        String haystack = normalise(reply);
        for (String phrase : phrases) {
            if (!haystack.contains(normalise(phrase))) {
                return new RunOutcome.Asserted(false, quote(phrase), "a reply without it");
            }
        }
        return new RunOutcome.Asserted(true, all(), "every one present");
    }

    @Override
    public String id() {
        return "contains-all";
    }

    private String all() {
        return phrases.size() == 1
            ? quote(phrases.get(0))
            : "all " + phrases.size() + " required phrases";
    }

    private static String quote(String phrase) {
        return "the phrase \"" + phrase + "\"";
    }

    /** Lower-cased with runs of whitespace collapsed, so a line break cannot hide a match. */
    private static String normalise(String text) {
        return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
