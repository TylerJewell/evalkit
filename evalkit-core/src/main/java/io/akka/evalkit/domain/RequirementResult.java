package io.akka.evalkit.domain;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * What one requirement did across every run of it.
 *
 * <p>A requirement runs more than once because one run cannot tell a requirement the
 * system meets from one it happened to meet. Five runs of a requirement the system
 * handles eight times in ten still all pass about a third of the time, so a single pass
 * is a weaker claim than it reads as.
 *
 * <p>The verdict over several runs is therefore stricter than the verdict over one.
 * {@link Verdict#PASSED} means every run passed. A requirement that passed some runs and
 * failed others is {@link Verdict#VARIED}, which is neither a pass nor a failure: the
 * system does the right thing sometimes, and the report says so rather than picking the
 * roll it liked.
 */
public record RequirementResult(Scenario scenario, List<Run> runs) {

    /** One run of the requirement, and how long the system took to answer it. */
    public record Run(RunOutcome outcome, Optional<Duration> latency) {

        public Run(RunOutcome outcome) {
            this(outcome, Optional.empty());
        }

        public Run {
            if (outcome == null) throw new IllegalArgumentException("run has no outcome");
            latency = latency == null ? Optional.empty() : latency;
        }
    }

    /**
     * What a requirement did, once its runs are taken together.
     *
     * <p>Ordered by how much the report can claim from it. A run that produced no
     * evidence says nothing about the system, so it cannot be read as a failure; an
     * undecided score is evidence the judge would not commit to; and a requirement that
     * disagreed with itself is a finding of its own.
     */
    public enum Verdict { PASSED, FAILED, VARIED, UNDECIDED, NO_RESULT }

    public RequirementResult {
        if (scenario == null) throw new IllegalArgumentException("requirement has no scenario");
        if (runs == null || runs.isEmpty()) {
            // A requirement with no runs would be counted somewhere, and every place it
            // could be counted would be a claim about a system that was never asked.
            throw new IllegalArgumentException(
                "requirement " + (scenario == null ? "?" : scenario.id()) + " has no runs");
        }
        runs = List.copyOf(runs);
    }

    public String id() {
        return scenario.id();
    }

    public int runCount() {
        return runs.size();
    }

    /** Outcomes that settled into a pass or a failure, which is what varying is measured over. */
    private List<RunOutcome> decided() {
        return runs.stream().map(Run::outcome)
            .filter(RunOutcome::isEvidence)
            .filter(o -> !o.needsReview())
            .toList();
    }

    /**
     * How many runs passed.
     *
     * <p>Counted over every run rather than over the decided ones, because the report
     * prints it beside the run count and a reader reads the two as a fraction of the same
     * thing.
     */
    public int passes() {
        return (int) runs.stream().filter(r -> r.outcome().passed()).count();
    }

    public Verdict verdict() {
        var decided = decided();
        if (decided.isEmpty()) {
            // Nothing settled. Either the judge would not commit, or no run produced
            // evidence at all, and those are different facts about the run.
            boolean anyReview = runs.stream().anyMatch(r -> r.outcome().needsReview());
            return anyReview ? Verdict.UNDECIDED : Verdict.NO_RESULT;
        }
        boolean anyPassed = decided.stream().anyMatch(RunOutcome::passed);
        boolean anyFailed = decided.stream().anyMatch(o -> !o.passed());
        if (anyPassed && anyFailed) return Verdict.VARIED;
        return anyPassed ? Verdict.PASSED : Verdict.FAILED;
    }

    /** Which quality measure settled this requirement, as the report names it. */
    public String measure() {
        if (scenario.specNode().isPresent()) return "specification node";
        if (!scenario.requiredPhrases().isEmpty()) return "required wording";
        return scenario.metric().map(m -> m.metricId().replace('-', ' '))
            .orElse("scenario judge");
    }

    /** What the report prints beside a failing requirement. */
    public String describe() {
        return runs.stream()
            .map(Run::outcome)
            .filter(o -> !o.passed())
            .findFirst()
            .orElse(runs.get(0).outcome())
            .describe();
    }
}
