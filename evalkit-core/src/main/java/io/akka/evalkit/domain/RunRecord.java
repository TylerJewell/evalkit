package io.akka.evalkit.domain;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything a report is rendered from.
 *
 * <p>The record is the result of a run and the report is a rendering of it. That order
 * matters: every figure the report prints is counted from the requirements here, and none
 * is stored beside them, so a report cannot disagree with the evidence it came from. It
 * also means a presentation can change without running a campaign again, and a dataset can
 * be re-scored without asking the system anything.
 *
 * @param measures      every quality measure the campaign registered, in the order the
 *                      report prints them. Measures no requirement used still appear, so
 *                      the report says what was available as well as what was exercised
 * @param recordPath    where this record was written, named in the report so a reader can
 *                      reach the evidence behind any figure
 */
public record RunRecord(RunSummary.Identity identity,
                        Optional<Policy> policy,
                        RunSummary.JudgeProfile judge,
                        int lanes,
                        int replyTimeoutSeconds,
                        List<RequirementResult> requirements,
                        List<String> measures,
                        RunSummary.Coverage coverage,
                        RunSummary.Spend spend,
                        String recordPath) {

    public RunRecord {
        if (identity == null) throw new IllegalArgumentException("record has no identity");
        if (requirements == null || requirements.isEmpty()) {
            throw new IllegalArgumentException("record holds no requirements");
        }
        requirements = List.copyOf(requirements);
        measures = measures == null ? List.of() : List.copyOf(measures);
        policy = policy == null ? Optional.empty() : policy;
        var ids = requirements.stream().map(RequirementResult::id).distinct().count();
        if (ids != requirements.size()) {
            // Two rows for one requirement would be counted twice in every panel.
            throw new IllegalArgumentException("two requirements share an id");
        }
    }

    public int requirementCount() {
        return requirements.size();
    }

    /** How many times each requirement ran, or 0 when they did not all run the same number. */
    public int repeats() {
        var counts = requirements.stream().map(RequirementResult::runCount).distinct().toList();
        return counts.size() == 1 ? counts.get(0) : 0;
    }

    public int runCount() {
        return requirements.stream().mapToInt(RequirementResult::runCount).sum();
    }

    /** Requirements in each verdict, in the order the first panel prints them. */
    public Map<RequirementResult.Verdict, Integer> byVerdict() {
        var counts = new LinkedHashMap<RequirementResult.Verdict, Integer>();
        for (var v : RequirementResult.Verdict.values()) counts.put(v, 0);
        for (var r : requirements) counts.merge(r.verdict(), 1, Integer::sum);
        return counts;
    }

    public List<RequirementResult> withVerdict(RequirementResult.Verdict verdict) {
        return requirements.stream().filter(r -> r.verdict() == verdict).toList();
    }

    /**
     * Requirements each measure checked, split by verdict.
     *
     * <p>Keyed on the registered measures rather than on what ran, so a measure the
     * campaign offered and nothing used is a row at zero instead of an absence.
     */
    public Map<String, Map<RequirementResult.Verdict, Integer>> byMeasure() {
        var rows = new LinkedHashMap<String, Map<RequirementResult.Verdict, Integer>>();
        for (String measure : measures) rows.put(measure, blank());
        for (var r : requirements) {
            rows.computeIfAbsent(r.measure(), ignored -> blank())
                .merge(r.verdict(), 1, Integer::sum);
        }
        return rows;
    }

    private static Map<RequirementResult.Verdict, Integer> blank() {
        var counts = new LinkedHashMap<RequirementResult.Verdict, Integer>();
        for (var v : RequirementResult.Verdict.values()) counts.put(v, 0);
        return counts;
    }

    /** Every score a judge gave, across every run, for the score distribution. */
    public List<Integer> judgedScores() {
        return requirements.stream()
            .flatMap(r -> r.runs().stream())
            .map(RequirementResult.Run::outcome)
            .filter(o -> o instanceof RunOutcome.Scored)
            .map(o -> ((RunOutcome.Scored) o).verdict().score())
            .toList();
    }

    /** Latencies the target reported, which a target that reports none leaves empty. */
    public List<Duration> latencies() {
        return requirements.stream()
            .flatMap(r -> r.runs().stream())
            .map(RequirementResult.Run::latency)
            .flatMap(Optional::stream)
            .toList();
    }

    /** How many runs ended for each reason that produced no evidence. */
    public Map<RunOutcome.Cause, Integer> causesOfNoResult() {
        var counts = new LinkedHashMap<RunOutcome.Cause, Integer>();
        for (var c : RunOutcome.Cause.values()) counts.put(c, 0);
        requirements.stream()
            .flatMap(r -> r.runs().stream())
            .map(RequirementResult.Run::outcome)
            .filter(o -> o instanceof RunOutcome.NotReached)
            .forEach(o -> counts.merge(((RunOutcome.NotReached) o).cause(), 1, Integer::sum));
        return counts;
    }

    public int unscoreable() {
        return (int) requirements.stream()
            .flatMap(r -> r.runs().stream())
            .map(RequirementResult.Run::outcome)
            .filter(o -> o instanceof RunOutcome.Unscoreable)
            .count();
    }
}
