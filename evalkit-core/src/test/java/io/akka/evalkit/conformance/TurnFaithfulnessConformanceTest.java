package io.akka.evalkit.conformance;

import io.akka.evalkit.metric.Finding;
import io.akka.evalkit.metric.TurnFaithfulness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The empty-finding behaviour is DeepEval's, pinned four times in its own fixture.
 *
 * <p>Source: {@code tests/test_metrics/test_turn_faithfulness_metric_empty_verdicts.py} at
 * commit bd10fa6. Upstream installs a stub model that raises if the metric ever calls it,
 * so the fallback is proved to happen without a provider rather than because a provider
 * returned nothing.
 */
@DisplayName("TurnFaithfulness · matches DeepEval's TurnFaithfulnessMetric")
class TurnFaithfulnessConformanceTest {

    private final TurnFaithfulness metric = new TurnFaithfulness();

    @Test
    @DisplayName("no claims to check scores 1.0 and calls no model")
    void emptyJudgementsScoreOne() {
        assertThat(metric.aggregate(List.of())).isEqualTo(1.0);
    }

    @Test
    @DisplayName("every claim supported scores 1.0")
    void allClaimsSupported() {
        var findings = List.of(
            Finding.affirmed("refunds run 30 days"),
            Finding.affirmed("no extra cost"));

        assertThat(metric.aggregate(findings)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("one unsupported claim among four scores 0.75")
    void oneUnsupportedClaim() {
        var findings = List.of(
            Finding.affirmed("refunds run 30 days"),
            Finding.affirmed("no extra cost"),
            Finding.affirmed("returns are free"),
            Finding.denied("refunds are instant", "no passage mentions timing"));

        assertThat(metric.aggregate(findings)).isEqualTo(0.75);
        assertThat(metric.withinThreshold(0.75)).isTrue();
    }

    @Test
    @DisplayName("no claim supported scores 0.0")
    void nothingSupported() {
        assertThat(metric.aggregate(List.of(Finding.denied("invented", "unsupported"))))
            .isEqualTo(0.0);
        assertThat(metric.withinThreshold(0.0)).isFalse();
    }

    @Test
    @DisplayName("it carries an id of its own, separate from turn-relevancy")
    void hasItsOwnIdentity() {
        // The two metrics share their arithmetic and ask the judge different questions,
        // so a score recorded under one is not a score under the other.
        assertThat(metric.ref().metricId()).isEqualTo("turn-faithfulness");
        assertThat(metric.ref().label()).isEqualTo("turn-faithfulness v1");
    }
}
