package io.akka.evalkit.domain;

import io.akka.evalkit.metric.Judgement;
import io.akka.evalkit.metric.ToolPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The measured column, and the cases that prove the report counts it.
 *
 * <p>A column nobody exercises reads the same as a column that works. These build reports
 * containing measured runs and assert the counts move.
 */
@DisplayName("RunOutcome.Measured · a computed result the report counts on its own")
class MeasuredOutcomeTest {

    private static RunOutcome measured(double value, double threshold) {
        return new RunOutcome.Measured("latency-budget", 1, value, threshold, value >= threshold);
    }

    private static CampaignReport report(List<RunOutcome> outcomes) {
        var precursors = new ArrayList<Precursor>();
        for (int i = 0; i < outcomes.size(); i++) precursors.add(Precursor.Fixture.named("f"));
        return CampaignReport.of(outcomes, precursors);
    }

    @Test
    @DisplayName("a measured run inside its threshold counts as passed and as measured")
    void withinThresholdCounts() {
        var report = report(List.of(measured(0.9, 0.8)));

        assertThat(report.measured()).isEqualTo(1);
        assertThat(report.measuredPassed()).isEqualTo(1);
        assertThat(report.measuredFailed()).isZero();
        assertThat(report.passed()).isEqualTo(1);
        assertThat(report.total()).isEqualTo(1);
    }

    @Test
    @DisplayName("a measured run outside its threshold counts as failed")
    void outsideThresholdCounts() {
        var report = report(List.of(measured(0.4, 0.8)));

        assertThat(report.measured()).isEqualTo(1);
        assertThat(report.measuredPassed()).isZero();
        assertThat(report.measuredFailed()).isEqualTo(1);
        assertThat(report.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("measured runs stay out of the scored column")
    void measuredIsNotScored() {
        // scored() names the runs that cost a model call. A metric computing a number
        // costs none, so a report mixing the two would overstate what the run spent.
        var report = report(List.of(measured(0.9, 0.8), measured(0.2, 0.8)));

        assertThat(report.measured()).isEqualTo(2);
        assertThat(report.scored()).isZero();
        assertThat(report.scoredPassed()).isZero();
        assertThat(report.scoredFailed()).isZero();
    }

    @Test
    @DisplayName("a measured run is evidence, unlike a run that produced nothing")
    void measuredIsEvidence() {
        assertThat(measured(0.9, 0.8).isEvidence()).isTrue();
        assertThat(measured(0.1, 0.8).isEvidence()).isTrue();
        assertThat(new RunOutcome.Unscoreable("filtered").isEvidence()).isFalse();
    }

    @Test
    @DisplayName("a measured run is never undecided")
    void measuredNeedsNoReview() {
        // A threshold decides. Only a judge has confidence to be borderline about.
        assertThat(measured(0.79, 0.8).needsReview()).isFalse();
        assertThat(measured(0.80, 0.8).needsReview()).isFalse();
    }

    @Test
    @DisplayName("the description names the metric, its version and both numbers")
    void describeCarriesTheMetricAndVersion() {
        assertThat(measured(0.9, 0.8).describe())
            .isEqualTo("latency-budget v1: 0.90 against 0.80");
    }

    @Test
    @DisplayName("waves accumulate the measured counts")
    void wavesAccumulate() {
        var first = report(List.of(measured(0.9, 0.8)));
        var second = report(List.of(measured(0.2, 0.8)));

        var both = first.plus(second);

        assertThat(both.measured()).isEqualTo(2);
        assertThat(both.measuredPassed()).isEqualTo(1);
        assertThat(both.measuredFailed()).isEqualTo(1);
    }

    @Test
    @DisplayName("a measured outcome without a metric id is refused")
    void requiresAMetricId() {
        assertThatThrownBy(() -> new RunOutcome.Measured("", 1, 0.9, 0.8, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("metric id");
    }

    @Test
    @DisplayName("a metric produces the outcome carrying its own id and version")
    void metricBuildsItsOwnOutcome() {
        var metric = ToolPermission.allowing("search_kb");
        var judgements = List.of(Judgement.affirmed("search_kb"),
                                 Judgement.denied("delete_account", "not allowed"));

        var outcome = (RunOutcome.Measured) metric.outcome(judgements);

        assertThat(outcome.metricId()).isEqualTo("tool-permission");
        assertThat(outcome.metricVersion()).isEqualTo(1);
        assertThat(outcome.value()).isEqualTo(0.5);
        assertThat(outcome.threshold()).isEqualTo(1.0);
        assertThat(outcome.withinThreshold()).isFalse();
        assertThat(outcome.passed()).isFalse();
    }

    @Test
    @DisplayName("mixed campaigns keep the three kinds in separate columns")
    void threeKindsStaySeparate() {
        var report = report(List.of(
            new RunOutcome.Asserted(true, "GenUC-1", "GenUC-1"),
            measured(0.9, 0.8),
            new RunOutcome.NotReached(RunOutcome.Cause.SETUP_FAILED, "no fixture",
                Precursor.Fixture.named("f"))));

        assertThat(report.asserted()).isEqualTo(1);
        assertThat(report.measured()).isEqualTo(1);
        assertThat(report.notReached()).isEqualTo(1);
        assertThat(report.total()).isEqualTo(3);
        assertThat(report.passed()).isEqualTo(2);
    }
}
