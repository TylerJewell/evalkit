package io.akka.evalkit.metric;

import java.util.List;

/**
 * The share of claims in a reply that the retrieved passages support.
 *
 * <p>Judges claims, where {@link TurnRelevancy} judges exchanges. The two share their
 * arithmetic and differ in what the judge is asked, which is why they are separate types
 * carrying separate ids.
 *
 * <p>Ported from DeepEval's {@code TurnFaithfulnessMetric}, Apache 2.0. The empty-verdict
 * behaviour comes from
 * {@code tests/test_metrics/test_turn_faithfulness_metric_empty_verdicts.py} at commit
 * bd10fa6, where a stub model raises if the metric calls it.
 */
public final class TurnFaithfulness implements Metric {

    private final MetricRef ref;
    private final double threshold;

    public TurnFaithfulness() {
        this(new MetricRef("turn-faithfulness", 1), 0.5);
    }

    private TurnFaithfulness(MetricRef ref, double threshold) {
        this.ref = ref;
        this.threshold = threshold;
    }

    public TurnFaithfulness scoringAtLeast(double newThreshold) {
        return new TurnFaithfulness(new MetricRef(ref.metricId(), ref.version() + 1), newThreshold);
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
     * Supported claims over judged claims.
     *
     * <p>A reply with no claims to check scores 1 and calls no model. Upstream pins that
     * with a stub that raises when asked to generate, so the fallback is reached without
     * a provider rather than by a provider returning nothing.
     */
    @Override
    public double aggregate(List<Judgement> judgements) {
        return Metric.shareAffirmed(judgements);
    }
}
