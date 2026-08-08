package io.akka.evalkit.metric;

import java.util.List;
import java.util.stream.IntStream;

/**
 * Whether each citation marker points at a passage that supports the claim beside it.
 *
 * <p>Stricter than faithfulness, and the difference is the reason this metric exists
 * separately. A reply whose claim is supported somewhere in the retrieved passages passes
 * a faithfulness check even when it cites the wrong passage. Attribution is what a reader
 * follows, so a correct claim with a wrong citation is a defect this reports.
 *
 * <p>Ported from DeepEval's {@code CitationFaithfulnessMetric}, Apache 2.0. The
 * misattribution case and the passage numbering come from
 * {@code tests/test_metrics/test_citation_faithfulness_metric.py} at commit bd10fa6.
 */
public final class CitationFaithfulness implements Metric {

    private final MetricRef ref;
    private final double threshold;

    public CitationFaithfulness() {
        this(new MetricRef("citation-faithfulness", 1), 1.0);
    }

    private CitationFaithfulness(MetricRef ref, double threshold) {
        this.ref = ref;
        this.threshold = threshold;
    }

    public CitationFaithfulness scoringAtLeast(double newThreshold) {
        return new CitationFaithfulness(new MetricRef(ref.metricId(), ref.version() + 1),
            newThreshold);
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
     * Retrieved passages numbered so that a {@code [N]} marker in the reply resolves.
     *
     * <p>Pure, and the part of the prompt that has to be right for the judge to answer the
     * question at all. A judge shown unnumbered passages cannot tell which one a marker
     * refers to, and it answers about support instead of attribution.
     *
     * <p>Numbering starts at 1, matching the markers a model writes.
     */
    public static String numberPassages(List<String> passages) {
        return IntStream.range(0, passages.size())
            .mapToObj(i -> "[" + (i + 1) + "] " + passages.get(i))
            .reduce((a, b) -> a + "\n" + b)
            .orElse("");
    }

    @Override
    public double aggregate(List<Judgement> judgements) {
        return Metric.shareAffirmed(judgements);
    }
}
