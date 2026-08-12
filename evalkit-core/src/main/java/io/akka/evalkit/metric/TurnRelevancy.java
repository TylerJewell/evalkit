package io.akka.evalkit.metric;

import java.util.List;

/**
 * The share of exchanges in a conversation whose reply answered what was asked.
 *
 * <p>One finding per user-and-assistant exchange, never per raw turn. A trailing user
 * message with no reply yet is not an exchange and is excluded before this metric sees
 * anything, so a conversation ending on a question does not score itself down.
 *
 * <p>Producing the findings needs a model. The arithmetic here does not, which is why
 * {@link #aggregate} is the half under test.
 *
 * <p>Ported from DeepEval's {@code TurnRelevancyMetric}, Apache 2.0. The score table and
 * the empty-finding fallback come from
 * {@code tests/test_metrics/test_turn_relevancy_aggregation.py} at commit bd10fa6, and
 * {@code TurnRelevancyConformanceTest} pins both.
 */
public final class TurnRelevancy implements Metric {

    private final MetricRef ref;
    private final double threshold;

    public TurnRelevancy() {
        this(new MetricRef("turn-relevancy", 1), 0.5);
    }

    private TurnRelevancy(MetricRef ref, double threshold) {
        this.ref = ref;
        this.threshold = threshold;
    }

    public TurnRelevancy scoringAtLeast(double newThreshold) {
        return new TurnRelevancy(new MetricRef(ref.metricId(), ref.version() + 1), newThreshold);
    }

    @Override
    public MetricRef ref() {
        return ref;
    }

    @Override
    public double threshold() {
        return threshold;
    }

    /**
     * Relevant exchanges over judged exchanges.
     *
     * <p>A conversation the judge could not read at all scores 1, which is the fallback
     * DeepEval documents and this port keeps. Scoring an unread conversation zero reports
     * a service as irrelevant on the strength of a judge that never answered.
     */
    @Override
    public double aggregate(List<Finding> findings) {
        return Metric.shareAffirmed(findings);
    }
}
