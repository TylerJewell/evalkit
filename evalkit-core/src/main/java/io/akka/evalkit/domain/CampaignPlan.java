package io.akka.evalkit.domain;

import java.util.List;

/**
 * A campaign, checked before anything is spent on it.
 *
 * <p>Everything here is knowable in the first second: whether the target can build the
 * fixtures, whether any scenario needs a judge at all, how much of the run will prove a
 * path rather than seed past it. A campaign that discovers any of it at minute forty has
 * wasted forty minutes and a provider bill.
 */
public record CampaignPlan(String id, List<Scenario> scenarios, Lanes lanes, Rubric rubric) {

    public CampaignPlan {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("campaign id required");
        if (scenarios == null || scenarios.isEmpty()) {
            throw new IllegalArgumentException("campaign " + id + " has no scenarios");
        }
        scenarios = List.copyOf(scenarios);
    }

    /** Whether a plan may run, and why not. */
    public sealed interface Check {
        record Ready(CampaignPlan plan) implements Check {}

        record Refused(List<String> reasons) implements Check {}
    }

    /**
     * Pre-flight.
     *
     * <p>The fixture check is the one that pays for itself: a name nobody implemented
     * would otherwise yield one {@link RunOutcome.NotReached} per scenario, at the end,
     * all saying the same thing.
     */
    public Check check(SystemUnderTest target) {
        var reasons = new java.util.ArrayList<String>();

        var missing = ScenarioRunner.unsupported(scenarios, target);
        if (!missing.isEmpty()) {
            reasons.add("target cannot build fixtures " + missing + "; it knows " + target.fixtures());
        }

        // Aimed at the wrong surface. Forty payment scenarios once ran against the
        // conversation, which emits an entirely different set of nodes; every one failed,
        // on every fixture, and looked like forty product defects.
        var known = target.emittableNodes();
        if (!known.isEmpty()) {
            var unreachable = scenarios.stream()
                .flatMap(s -> s.specNode().stream())
                .filter(node -> !known.contains(node))
                .distinct()
                .sorted()
                .toList();
            if (!unreachable.isEmpty()) {
                reasons.add("target cannot emit nodes " + unreachable
                    + "; no input would satisfy those assertions");
            }
        }

        if (scenarios.isEmpty()) {
            reasons.add("no scenarios to run");
        }

        return reasons.isEmpty() ? new Check.Ready(this) : new Check.Refused(reasons);
    }

    /** Scenarios that need a model to grade them. */
    public int judged() {
        return (int) scenarios.stream().filter(Scenario::needsJudge).count();
    }

    /** Scenarios resolving to a decision, which code can assert instead. */
    public int asserted() {
        return scenarios.size() - judged();
    }

    /** Scenarios that will walk their path rather than seed past it. */
    public int walked() {
        return (int) scenarios.stream().filter(s -> s.precursor().provesReachability()).count();
    }

    public String summary() {
        var note = walked() == 0
            ? " — nothing walks a path, so this campaign cannot detect an unreachable state"
            : "";
        return "%s: %d scenarios (%d judged, %d asserted), %d walked, %d lanes%s".formatted(
            id, scenarios.size(), judged(), asserted(), walked(), lanes.configured(), note);
    }
}
