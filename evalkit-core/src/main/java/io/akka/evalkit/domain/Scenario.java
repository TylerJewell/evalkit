package io.akka.evalkit.domain;

import java.util.Optional;

/**
 * One thing to test: get the system into a state, say something, expect something.
 *
 * <p>A run of a scenario carries a transcript, a score and a session id. The scenario
 * itself holds the fields below and how to reach the state, so a report of past runs
 * still yields a suite.
 *
 * @param specNode the requirement this exercises, when it names one. Present for 510 of
 *                 the 514 claim-flow scenarios and for none of the FAQ ones, which is the
 *                 signal used to decide whether a judge is needed at all
 */
public record Scenario(String id,
                       Optional<String> specNode,
                       Optional<io.akka.evalkit.metric.MetricRef> metric,
                       java.util.List<String> requiredPhrases,
                       Precursor precursor,
                       String gradedTurn,
                       String expectedOutcome) {

    /** A scenario requiring no particular wording, which is most of them. */
    public Scenario(String id, Optional<String> specNode,
                    Optional<io.akka.evalkit.metric.MetricRef> metric, Precursor precursor,
                    String gradedTurn, String expectedOutcome) {
        this(id, specNode, metric, java.util.List.of(), precursor, gradedTurn, expectedOutcome);
    }

    /** A scenario naming no metric, which is most of them. */
    public Scenario(String id, Optional<String> specNode, Precursor precursor,
                    String gradedTurn, String expectedOutcome) {
        this(id, specNode, Optional.empty(), precursor, gradedTurn, expectedOutcome);
    }

    public Scenario {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("scenario id required");
        if (gradedTurn == null || gradedTurn.isBlank()) {
            throw new IllegalArgumentException("scenario " + id + " has nothing to say");
        }
        if (expectedOutcome == null || expectedOutcome.isBlank()) {
            throw new IllegalArgumentException("scenario " + id + " expects nothing");
        }
        specNode = specNode == null ? Optional.empty() : specNode;
        metric = metric == null ? Optional.empty() : metric;
        requiredPhrases = requiredPhrases == null
            ? java.util.List.of() : java.util.List.copyOf(requiredPhrases);
        // Two expectations settle the same run two ways, and the report has one row per
        // scenario. Split it into two scenarios and each gets its own row.
        var named = new java.util.ArrayList<String>();
        if (specNode.isPresent()) named.add("a specification node");
        if (metric.isPresent()) named.add("a metric");
        if (!requiredPhrases.isEmpty()) named.add("required phrases");
        if (named.size() > 1) {
            throw new IllegalArgumentException("scenario " + id
                + " states more than one expectation: " + String.join(" and ", named));
        }
        if (requiredPhrases.stream().anyMatch(p -> p == null || p.isBlank())) {
            throw new IllegalArgumentException(
                "scenario " + id + " has a blank required phrase");
        }
        if (precursor == null) precursor = new Precursor.None();
    }

    /**
     * Whether a model has to judge this, or code can assert it.
     *
     * <p>A scenario naming a spec node is exercising a decision that is a pure function
     * here. Judging one is waste that adds variance: it either produced the expected
     * outcome or it did not.
     */
    public boolean needsJudge() {
        return specNode.isEmpty() && metric.isEmpty() && requiredPhrases.isEmpty();
    }

    /** The same scenario, reached a different way. */
    public Scenario via(Precursor other) {
        return new Scenario(id, specNode, metric, requiredPhrases, other,
            gradedTurn, expectedOutcome);
    }

    /** The same scenario, settled by a metric rather than by a judge. */
    public Scenario measuredBy(io.akka.evalkit.metric.MetricRef ref) {
        return new Scenario(id, Optional.empty(), Optional.of(ref), java.util.List.of(),
            precursor, gradedTurn, expectedOutcome);
    }

    /**
     * The same scenario, settled by whether the reply stated these phrases.
     *
     * <p>For an answer whose correctness is a wording the policy requires &mdash; a
     * refusal that has to name the alternative, an answer that has to state the term it
     * turns on. A model is not needed to decide whether a reply contains a sentence.
     */
    public Scenario requiring(String... phrases) {
        return new Scenario(id, Optional.empty(), Optional.empty(),
            java.util.List.of(phrases), precursor, gradedTurn, expectedOutcome);
    }
}
