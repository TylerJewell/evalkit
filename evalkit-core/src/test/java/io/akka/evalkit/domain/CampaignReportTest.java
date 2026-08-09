package io.akka.evalkit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CampaignReport · what a campaign may claim")
class CampaignReportTest {

    private static final Rubric RUBRIC = new Rubric("scenario-judge", 2,
        "{replay_history}{simulation_history}{system_output}{expected_outcome}");

    private static RunOutcome scored(int score) {
        return new RunOutcome.Scored(Verdict.of("s", RUBRIC, score, ""));
    }

    /** Builds a report from outcomes, all seeded unless stated. */
    private static CampaignReport report(List<RunOutcome> outcomes) {
        var precursors = new ArrayList<Precursor>();
        for (int i = 0; i < outcomes.size(); i++) precursors.add(Precursor.Fixture.named("f"));
        return CampaignReport.of(outcomes, precursors);
    }

    @Test
    @DisplayName("a run that never reached its state is not a failing system")
    void notReachedIsNotFailure() {
        // The mistake this prevents: setup that did not land, reported as a wrong answer.
        var r = report(List.of(scored(9), scored(9),
            new RunOutcome.NotReached(RunOutcome.Cause.SETUP_FAILED, "OTP step timed out", Precursor.Fixture.named("f"))));

        assertThat(r.judged()).isEqualTo(2);
        assertThat(r.total()).isEqualTo(3);
        assertThat(r.failed()).isZero();
        assertThat(r.notReached()).isEqualTo(1);
        // Two of two judged passed. The third is absent evidence, not a zero.
        assertThat(r.passRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a judge that refused is not a failing system either")
    void unscoreableIsNotFailure() {
        // Gemini's content filter refused a GenUC-02 transcript during calibration.
        var r = report(List.of(scored(10),
            new RunOutcome.Unscoreable("content filter refused the transcript")));

        assertThat(r.unscoreable()).isEqualTo(1);
        assertThat(r.failed()).isZero();
        assertThat(r.judged()).isEqualTo(1);
    }

    @Test
    @DisplayName("PARTIAL is counted as undecided, not as a near miss")
    void reviewIsSeparate() {
        var r = report(List.of(scored(9), scored(5), scored(2)));

        assertThat(r.passed()).isEqualTo(1);
        assertThat(r.review()).isEqualTo(1);
        assertThat(r.failed()).isEqualTo(1);
    }

    @Test
    @DisplayName("too little evidence makes the pass rate untrustworthy")
    void untrustworthyWhenEvidenceIsThin() {
        var outcomes = new ArrayList<RunOutcome>();
        for (int i = 0; i < 8; i++) outcomes.add(scored(9));
        outcomes.add(new RunOutcome.NotReached(RunOutcome.Cause.SETUP_FAILED, "setup failed", Precursor.Fixture.named("f")));
        outcomes.add(new RunOutcome.Unscoreable("filter"));

        var r = report(outcomes);

        // 8 of 8 judged passed — and a fifth of the campaign produced no evidence at all.
        assertThat(r.passRate()).isEqualTo(1.0);
        assertThat(r.isTrustworthy()).isFalse();
        assertThat(r.summary()).contains("not trustworthy");
    }

    @Test
    @DisplayName("too many undecided runs make the pass rate untrustworthy")
    void untrustworthyWhenRubricDoesNotDiscriminate() {
        // Calibration put judge agreement on PARTIAL at 53%. A campaign sitting mostly in
        // that band is measuring where a boundary falls, not how a system behaved.
        var outcomes = new ArrayList<RunOutcome>();
        for (int i = 0; i < 6; i++) outcomes.add(scored(9));
        for (int i = 0; i < 4; i++) outcomes.add(scored(5));

        assertThat(report(outcomes).isTrustworthy()).isFalse();
    }

    @Test
    @DisplayName("a healthy campaign is trustworthy")
    void trustworthy() {
        var outcomes = new ArrayList<RunOutcome>();
        for (int i = 0; i < 18; i++) outcomes.add(scored(9));
        outcomes.add(scored(5));
        outcomes.add(scored(2));

        var r = report(outcomes);

        assertThat(r.isTrustworthy()).isTrue();
        assertThat(r.summary()).doesNotContain("not trustworthy");
    }

    @Test
    @DisplayName("an entirely seeded campaign says so, because it proved no path")
    void seededCampaignDeclaresItself() {
        // Seeding is what makes a large corpus affordable, and it is also how a suite
        // stops noticing that a state became unreachable.
        var r = report(List.of(scored(9), scored(9)));

        assertThat(r.provesAnyReachability()).isFalse();
        assertThat(r.summary()).contains("entirely seeded");
    }

    @Test
    @DisplayName("a walked run counts as having proved its path")
    void walkedCounts() {
        var r = CampaignReport.of(
            List.of(scored(9), scored(9)),
            List.of(Precursor.Fixture.named("f"), new Precursor.Replay(List.of("hi", "yes"))));

        assertThat(r.walked()).isEqualTo(1);
        assertThat(r.provesAnyReachability()).isTrue();
        assertThat(r.summary()).doesNotContain("entirely seeded");
    }

    @Test
    @DisplayName("only a replay proves reachability")
    void reachabilitySemantics() {
        assertThat(new Precursor.Replay(List.of("hi")).provesReachability()).isTrue();
        assertThat(Precursor.Fixture.named("authenticated").provesReachability()).isFalse();
        assertThat(new Precursor.None().provesReachability()).isFalse();
    }

    @Test
    @DisplayName("a deterministic result is a pass or a fail, never undecided")
    void assertionsAreNeverUndecided() {
        var outcomes = List.<RunOutcome>of(
            SpecNodeMatch.assertReached("GenUC-17a", Optional.of("GenUC-17a")),
            SpecNodeMatch.assertReached("GenUC-17b", Optional.of("GLOB-04b")),
            SpecNodeMatch.assertReached("SoC-03e", Optional.of("GLOB-04a")),
            new RunOutcome.Scored(new Verdict("FAQ-01", "scenario-judge", 2, 5,
                Band.of(5), "")));
        var report = CampaignReport.of(outcomes,
            List.of(Precursor.Fixture.named("f"), Precursor.Fixture.named("f"), Precursor.Fixture.named("f"), Precursor.Fixture.named("f")));

        assertThat(report.asserted()).isEqualTo(3);
        assertThat(report.assertedPassed()).isEqualTo(1);
        assertThat(report.assertedFailed()).isEqualTo(2);

        // The whole point: the only undecided came from the model, and the two columns
        // add back to the totals rather than double-counting.
        assertThat(report.review()).isEqualTo(1);
        assertThat(report.scoredPassed() + report.assertedPassed()).isEqualTo(report.passed());
        assertThat(report.scoredFailed() + report.assertedFailed()).isEqualTo(report.failed());
    }
}
