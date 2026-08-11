package io.akka.evalkit.domain;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Turning verdicts into something reportable, without overclaiming.
 *
 * <p>Everything here works in {@link Band}s. That is the whole point: the temptation is to
 * average the 1&ndash;10 scores and report "7.4 versus 5.5", and that number is not
 * defensible unless both sides were scored by the same judge, on the same model, at the
 * same temperature. Band movement survives assumptions that a mean does not.
 */
public final class Scoring {

    private Scoring() {}

    public record Distribution(Map<Band, Integer> counts, int total) {

        public int count(Band band) {
            return counts.getOrDefault(band, 0);
        }

        public double share(Band band) {
            return total == 0 ? 0 : (double) count(band) / total;
        }

        public int passed() {
            return count(Band.FAITHFUL);
        }
    }

    public static Distribution distribution(List<Verdict> verdicts) {
        var counts = new EnumMap<Band, Integer>(Band.class);
        for (Verdict v : verdicts) counts.merge(v.band(), 1, Integer::sum);
        return new Distribution(counts, verdicts.size());
    }

    /**
     * How one set of verdicts moved against another.
     *
     * @param improved  scenarios that moved to a higher band
     * @param regressed scenarios that moved to a lower band
     * @param unchanged same band in both
     * @param onlyIn    scenarios present in one side only, which are not evidence either way
     */
    public record Comparison(List<String> improved, List<String> regressed,
                             List<String> unchanged, List<String> onlyIn) {

        public boolean isRegression() {
            return !regressed.isEmpty();
        }
    }

    /**
     * Compares two verdict sets scenario by scenario.
     *
     * <p><b>Refuses to compare across rubric versions.</b> Scoring a baseline with v2 and a
     * candidate with v3 and calling the difference an improvement attributes to the system
     * a change that was made to the ruler. If that comparison is genuinely wanted, re-score
     * the baseline transcripts on v3 first &mdash; which costs nothing but judge calls,
     * because {@link Transcript}s are kept.
     */
    /**
     * Compares two runs that each state the rules the system was given.
     *
     * <p><b>Refuses across policy versions, for the reason it refuses across rubric
     * versions.</b> A rubric change moves the ruler; a policy change moves what was being
     * measured. Either one turns a difference between two runs into a difference nobody
     * can attribute, and a policy change is the easier of the two to make by accident,
     * because it is a change to the system rather than to this kit.
     */
    public static Comparison compare(Policy baselinePolicy, List<Verdict> baseline,
                                     Policy candidatePolicy, List<Verdict> candidate) {
        if (!baselinePolicy.equals(candidatePolicy)) {
            throw new IllegalArgumentException(
                "cannot compare runs under different policies: "
                    + baselinePolicy.label() + " and " + candidatePolicy.label()
                    + " — the system was told different things, so a difference between "
                    + "these runs is not a difference in how well it did");
        }
        return compare(baseline, candidate);
    }

    public static Comparison compare(List<Verdict> baseline, List<Verdict> candidate) {
        var left = index(baseline);
        var right = index(candidate);

        var rubrics = new java.util.TreeSet<String>();
        baseline.forEach(v -> rubrics.add(v.rubricId() + " v" + v.rubricVersion()));
        candidate.forEach(v -> rubrics.add(v.rubricId() + " v" + v.rubricVersion()));
        if (rubrics.size() > 1) {
            throw new IllegalArgumentException(
                "cannot compare verdicts from different rubrics: " + rubrics
                    + " — re-score the baseline transcripts on the newer rubric instead");
        }

        var improved = new java.util.ArrayList<String>();
        var regressed = new java.util.ArrayList<String>();
        var unchanged = new java.util.ArrayList<String>();
        var onlyIn = new java.util.ArrayList<String>();

        for (var entry : left.entrySet()) {
            Verdict other = right.get(entry.getKey());
            if (other == null) {
                onlyIn.add(entry.getKey());
                continue;
            }
            int move = other.band().compareTo(entry.getValue().band());
            if (move > 0) improved.add(entry.getKey());
            else if (move < 0) regressed.add(entry.getKey());
            else unchanged.add(entry.getKey());
        }
        right.keySet().stream().filter(k -> !left.containsKey(k)).forEach(onlyIn::add);

        improved.sort(String::compareTo);
        regressed.sort(String::compareTo);
        unchanged.sort(String::compareTo);
        onlyIn.sort(String::compareTo);
        return new Comparison(improved, regressed, unchanged, onlyIn);
    }

    private static Map<String, Verdict> index(List<Verdict> verdicts) {
        var out = new java.util.LinkedHashMap<String, Verdict>();
        for (Verdict v : verdicts) {
            if (out.putIfAbsent(v.scenarioName(), v) != null) {
                // A scenario can run many times; the reference export carries 1 to 61
                // runs of a single scenario. The caller decides which run counts, because
                // iteration order would otherwise decide it.
                throw new IllegalArgumentException(
                    "more than one verdict for " + v.scenarioName()
                        + " — aggregate repeated runs before comparing");
            }
        }
        return out;
    }
}
