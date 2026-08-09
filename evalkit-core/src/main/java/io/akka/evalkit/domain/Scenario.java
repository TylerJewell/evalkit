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
                       Precursor precursor,
                       String gradedTurn,
                       String expectedOutcome) {

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
        if (specNode.isPresent() && metric.isPresent()) {
            // Two expectations settle the same run two ways, and the report has one row
            // per scenario. Split it into two scenarios and each gets its own row.
            throw new IllegalArgumentException(
                "scenario " + id + " names both a specification node and a metric");
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
        return specNode.isEmpty() && metric.isEmpty();
    }

    /** The same scenario, reached a different way. */
    public Scenario via(Precursor other) {
        return new Scenario(id, specNode, metric, other, gradedTurn, expectedOutcome);
    }

    /** The same scenario, settled by a metric rather than by a judge. */
    public Scenario measuredBy(io.akka.evalkit.metric.MetricRef ref) {
        return new Scenario(id, Optional.empty(), Optional.of(ref), precursor,
            gradedTurn, expectedOutcome);
    }
}
