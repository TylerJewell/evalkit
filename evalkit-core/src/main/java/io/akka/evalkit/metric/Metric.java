package io.akka.evalkit.metric;

import java.util.List;

/**
 * A score computed from judgements about parts of a reply.
 *
 * <p><b>The two halves are separated because only one of them can be tested.</b>
 * Producing judgements may require a model, and a model samples: the same transcript
 * scores differently on consecutive runs. Turning judgements into a number is a pure
 * function, it is where a port goes wrong, and it is silent when it does.
 *
 * <p>{@link #aggregate} therefore takes judgements rather than a recording, so the
 * arithmetic runs in a unit test with no provider, no key and no network. The conformance
 * fixtures under {@code io.akka.evalkit.conformance} exercise exactly this method.
 *
 * <p>A deterministic metric produces its own judgements by comparison and never reaches a
 * model. {@link ToolPermission} is one, and its judgements are as testable as its
 * arithmetic.
 */
public interface Metric {

    MetricRef ref();

    /**
     * The score a run must reach to count as within budget.
     *
     * <p>Part of {@link #ref()}'s version: changing it changes what every recorded score
     * under the old value meant.
     */
    double threshold();

    /**
     * Judgements to a score between 0 and 1.
     *
     * <p>Pure. The same judgements produce the same score on any machine at any time.
     *
     * @param judgements in the order they were produced, which some metrics use and
     *                   others ignore
     */
    double aggregate(List<Judgement> judgements);

    /** Whether a score counts as within budget. */
    default boolean withinThreshold(double score) {
        return score >= threshold();
    }

    /**
     * The score as an outcome a campaign can report.
     *
     * <p>Carries the metric id and version, so a run recorded today stays interpretable
     * after somebody changes a threshold.
     */
    default io.akka.evalkit.domain.RunOutcome outcome(List<Judgement> judgements) {
        double value = aggregate(judgements);
        return new io.akka.evalkit.domain.RunOutcome.Measured(
            ref().metricId(), ref().version(), value, threshold(), withinThreshold(value));
    }

    /**
     * The share of judgements that went the metric's way.
     *
     * <p>The arithmetic most metrics use, kept here so that a metric which needs it does
     * not restate it and drift.
     *
     * <p>An empty list scores 1. Nothing was judged, so nothing was found wrong, and the
     * alternative reports a service as failing on evidence nobody produced. Metrics that
     * disagree override this method and say so.
     */
    static double shareAffirmed(List<Judgement> judgements) {
        if (judgements.isEmpty()) return 1.0;
        long affirmed = judgements.stream().filter(Judgement::affirmed).count();
        return (double) affirmed / judgements.size();
    }
}
