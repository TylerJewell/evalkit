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
public record CampaignPlan(String id, List<Scenario> scenarios, Lanes lanes, Rubric rubric,
                           java.util.Optional<Policy> policy, int repeats) {

    /** A campaign that does not state the rules the system was given. */
    public CampaignPlan(String id, List<Scenario> scenarios, Lanes lanes, Rubric rubric) {
        this(id, scenarios, lanes, rubric, java.util.Optional.empty(), 1);
    }

    public CampaignPlan(String id, List<Scenario> scenarios, Lanes lanes, Rubric rubric,
                        java.util.Optional<Policy> policy) {
        this(id, scenarios, lanes, rubric, policy, 1);
    }

    public CampaignPlan {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("campaign id required");
        if (scenarios == null || scenarios.isEmpty()) {
            throw new IllegalArgumentException("campaign " + id + " has no scenarios");
        }
        if (repeats < 1) throw new IllegalArgumentException("a campaign runs each scenario once");
        scenarios = List.copyOf(scenarios);
        policy = policy == null ? java.util.Optional.empty() : policy;
    }

    /** The same campaign, recorded as having run under these rules. */
    public CampaignPlan under(Policy stated) {
        return new CampaignPlan(id, scenarios, lanes, rubric, java.util.Optional.of(stated),
            repeats);
    }

    /**
     * The same campaign with each scenario run this many times.
     *
     * <p>One run cannot tell a requirement the system meets from one it happened to meet:
     * a requirement the system handles eight times in ten still passes five runs about a
     * third of the time. Repeating costs provider spend in proportion, and the report says
     * what the count bought.
     */
    public CampaignPlan repeating(int times) {
        return new CampaignPlan(id, scenarios, lanes, rubric, policy, times);
    }

    /** Runs this campaign will execute, which is what it is billed for. */
    public int runs() {
        return scenarios.size() * repeats;
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

        // A tool the target cannot break answers normally, and the scenario reports the
        // system recovering from a failure that never happened.
        var breakable = target.breakableTools();
        var unbreakable = scenarios.stream()
            .flatMap(s -> s.precursor().brokenTools().stream())
            .filter(tool -> !breakable.contains(tool))
            .distinct()
            .sorted()
            .toList();
        if (!unbreakable.isEmpty()) {
            reasons.add("target cannot break tools " + unbreakable
                + "; those scenarios would run against working tools");
        }

        if (scenarios.isEmpty()) {
            reasons.add("no scenarios to run");
        }

        reasons.addAll(corpusDefects());

        return reasons.isEmpty() ? new Check.Ready(this) : new Check.Refused(reasons);
    }

    /**
     * Defects in the corpus rather than in the target.
     *
     * <p>Every published benchmark that has audited its own tasks has found some of
     * these. They are checked here because each one produces a result that reads as a
     * finding about the system: a duplicated id counts one requirement twice, a phrase
     * the scenario itself contradicts fails on every run for every service, and a
     * scenario that expects nothing passes whatever the system says.
     */
    private List<String> corpusDefects() {
        var reasons = new java.util.ArrayList<String>();

        // Two rows for one requirement. Every panel counts it twice, and the second
        // result silently replaces the first wherever results are keyed by id.
        var seen = new java.util.HashSet<String>();
        var duplicated = scenarios.stream()
            .map(Scenario::id)
            .filter(id -> !seen.add(id))
            .distinct()
            .sorted()
            .toList();
        if (!duplicated.isEmpty()) {
            reasons.add("two scenarios share the ids " + duplicated
                + "; each would be counted twice");
        }

        // A phrase the scenario's own expected outcome contradicts cannot be satisfied by
        // any reply, so it fails on every run and reads as a service that never complies.
        var contradictory = scenarios.stream()
            .filter(s -> !s.requiredPhrases().isEmpty())
            .filter(s -> s.expectedOutcome().toLowerCase(java.util.Locale.ROOT)
                .contains("does not") || s.expectedOutcome()
                .toLowerCase(java.util.Locale.ROOT).contains("must not"))
            .filter(s -> s.requiredPhrases().stream().anyMatch(phrase ->
                s.expectedOutcome().toLowerCase(java.util.Locale.ROOT)
                    .contains("not " + phrase.toLowerCase(java.util.Locale.ROOT))))
            .map(Scenario::id)
            .sorted()
            .toList();
        if (!contradictory.isEmpty()) {
            reasons.add("scenarios " + contradictory + " require wording their own expected "
                + "outcome says must not appear; no reply satisfies both");
        }

        // A scenario naming a node it also says nothing about is the shape a corpus takes
        // when a template was filled in halfway.
        var placeholder = scenarios.stream()
            .filter(s -> s.gradedTurn().equals(s.expectedOutcome()))
            .map(Scenario::id)
            .sorted()
            .toList();
        if (!placeholder.isEmpty()) {
            reasons.add("scenarios " + placeholder + " expect back exactly what they said; "
                + "an unfilled template passes or fails for reasons nobody chose");
        }

        return reasons;
    }

    /**
     * Pre-flight, including the evaluators the run expects to be bound.
     *
     * <p>An evaluator binds to an agent through configuration rather than through code, so a
     * name is only checked when something looks it up. A binding naming a component nobody
     * registered scores nothing and reports nothing, which reads as an agent that behaved.
     *
     * @param configured the evaluator component ids named in configuration
     * @param registered the evaluator component ids the service registered
     */
    public Check check(SystemUnderTest target, List<String> configured, List<String> registered) {
        var checked = check(target);
        var reasons = new java.util.ArrayList<String>(
            checked instanceof Check.Refused refused ? refused.reasons() : List.of());

        var unregistered = configured.stream()
            .filter(id -> !registered.contains(id))
            .distinct()
            .sorted()
            .toList();
        if (!unregistered.isEmpty()) {
            reasons.add("no evaluator is registered for " + unregistered
                + "; those bindings would score nothing and report nothing");
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
