package io.akka.evalkit.domain;

/**
 * Turns a finished observation into an outcome.
 *
 * <p>The return type is {@link RunOutcome} and not a number. A {@code double} has no value
 * meaning "nobody assessed this", so a scorer that failed would have to return a figure,
 * and the report would count that figure as a score.
 *
 * <p>Three families implement this. Comparison against a stated expectation returns
 * {@link RunOutcome.Asserted}. Computation against a threshold returns
 * {@link RunOutcome.Measured}. A judge returns {@link RunOutcome.Scored}, or
 * {@link RunOutcome.Inconclusive} when it could not answer.
 */
@FunctionalInterface
public interface Scorer {

    RunOutcome score(Observation observation);

    /**
     * What produced the outcome, for the report row.
     *
     * <p>A judge names its rubric and version, a metric names its metric and version, and
     * a comparison names the node it expected. A row saying only "failed" leaves a reader
     * with nothing to look up.
     */
    default String id() {
        return getClass().getSimpleName();
    }
}
