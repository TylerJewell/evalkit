package io.akka.evalkit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the report's arithmetic must satisfy for any campaign, not only the ones written.
 *
 * <p>The example tests cover campaigns somebody thought of. These generate campaigns
 * nobody would write: every run measured, every run refused, one scenario, ten thousand.
 * The derived counts subtract columns from each other, so a variant added without its own
 * column turns one of them negative and no example test would reach it.
 */
@DisplayName("CampaignReport · invariants that hold for any campaign")
class ReportArithmeticPropertyTest {

    private static final Rubric RUBRIC = new Rubric("scenario-judge", 2,
        "{replay_history}{simulation_history}{system_output}{expected_outcome}");

    private static final int TRIALS = 2_000;

    private record Campaign(List<RunOutcome> outcomes, List<Precursor> precursors) {}

    private static Campaign random(Random random, int size) {
        var outcomes = new ArrayList<RunOutcome>(size);
        var precursors = new ArrayList<Precursor>(size);
        for (int i = 0; i < size; i++) {
            outcomes.add(switch (random.nextInt(6)) {
                case 0 -> new RunOutcome.Scored(
                    Verdict.of("s" + i, RUBRIC, 1 + random.nextInt(10), ""));
                case 1 -> new RunOutcome.Asserted(random.nextBoolean(), "n" + i, "n" + i);
                case 2 -> new RunOutcome.Measured("m", 1, random.nextDouble(), 0.5,
                    random.nextBoolean());
                case 3 -> new RunOutcome.NotReached(RunOutcome.Cause.SETUP_FAILED, "no fixture",
                    new Precursor.None());
                case 4 -> new RunOutcome.NotReached(RunOutcome.Cause.NO_REPLY, "silence",
                    new Precursor.None());
                default -> new RunOutcome.Unscoreable("filtered");
            });
            precursors.add(random.nextBoolean()
                ? Precursor.replay("hello")
                : Precursor.Fixture.named("ready"));
        }
        return new Campaign(outcomes, precursors);
    }

    private static CampaignReport reportOf(Campaign campaign) {
        return CampaignReport.of(campaign.outcomes(), campaign.precursors());
    }

    @Test
    @DisplayName("the columns always add up to the total")
    void theColumnsAlwaysAddUp() {
        var random = new Random(20260808L);

        for (int trial = 0; trial < TRIALS; trial++) {
            var report = reportOf(random(random, random.nextInt(40)));

            assertThat(report.judged() + report.notReached() + report.unscoreable())
                .as("total")
                .isEqualTo(report.total());
            assertThat(report.passed() + report.review() + report.failed())
                .as("judged")
                .isEqualTo(report.judged());
            assertThat(report.setupFailed() + report.noReply())
                .as("not reached")
                .isEqualTo(report.notReached());
        }
    }

    @Test
    @DisplayName("no derived count is ever negative")
    void noDerivedCountGoesNegative() {
        var random = new Random(20260809L);

        for (int trial = 0; trial < TRIALS; trial++) {
            var report = reportOf(random(random, random.nextInt(40)));

            // Each of these subtracts other columns. A variant that increments passed or
            // failed without a column of its own drives one below zero, and a report with
            // a negative count is a report nobody can read.
            assertThat(report.assertedFailed()).as("assertedFailed").isNotNegative();
            assertThat(report.measuredFailed()).as("measuredFailed").isNotNegative();
            assertThat(report.scored()).as("scored").isNotNegative();
            assertThat(report.scoredPassed()).as("scoredPassed").isNotNegative();
            assertThat(report.scoredFailed()).as("scoredFailed").isNotNegative();
        }
    }

    @Test
    @DisplayName("each family's passed and failed sum to its own count")
    void eachFamilySumsToItself() {
        var random = new Random(20260810L);

        for (int trial = 0; trial < TRIALS; trial++) {
            var report = reportOf(random(random, random.nextInt(40)));

            // Comparison and computation have two states. A reply either reached the node
            // it named or it did not, and a number is either inside its threshold or
            // outside, so these two sum without a third term.
            assertThat(report.assertedPassed() + report.assertedFailed())
                .as("asserted splits in two")
                .isEqualTo(report.asserted());
            assertThat(report.measuredPassed() + report.measuredFailed())
                .as("measured splits in two")
                .isEqualTo(report.measured());

            // The judged family has three. A judge can be unsure, the middle band records
            // that, and undecided belongs to neither passed nor failed.
            assertThat(report.scoredPassed() + report.scoredFailed() + report.review())
                .as("scored splits in three")
                .isEqualTo(report.scored());
        }
    }

    @Test
    @DisplayName("only the judged family ever reports an undecided run")
    void undecidedBelongsToTheJudgeAlone() {
        var random = new Random(20260816L);

        for (int trial = 0; trial < TRIALS; trial++) {
            var report = reportOf(random(random, random.nextInt(40)));

            // Every undecided run came from a judge, so review can never exceed the runs
            // a judge settled. A figure that broke this would put a dash column's runs
            // into the undecided row, which is the defect the split columns exist to show.
            assertThat(report.review()).isLessThanOrEqualTo(report.scored());
        }
    }

    @Test
    @DisplayName("running a campaign in waves totals the same as running it whole")
    void wavesTotalTheSameAsOnePass() {
        var random = new Random(20260811L);

        for (int trial = 0; trial < 500; trial++) {
            var whole = random(random, 1 + random.nextInt(60));
            int cut = random.nextInt(whole.outcomes().size() + 1);

            var head = new Campaign(whole.outcomes().subList(0, cut),
                whole.precursors().subList(0, cut));
            var tail = new Campaign(whole.outcomes().subList(cut, whole.outcomes().size()),
                whole.precursors().subList(cut, whole.outcomes().size()));

            // CampaignWorkflow folds one wave at a time into state, so a report that
            // accumulated differently from a single pass would make a resumed campaign
            // disagree with an uninterrupted one.
            assertThat(reportOf(head).plus(reportOf(tail))).isEqualTo(reportOf(whole));
        }
    }

    @Test
    @DisplayName("an empty report is the identity of accumulation")
    void emptyIsTheIdentity() {
        var random = new Random(20260812L);

        for (int trial = 0; trial < 200; trial++) {
            var report = reportOf(random(random, random.nextInt(30)));

            assertThat(CampaignReport.empty().plus(report)).isEqualTo(report);
            assertThat(report.plus(CampaignReport.empty())).isEqualTo(report);
        }
    }

    @Test
    @DisplayName("accumulation does not depend on the order the waves arrive")
    void accumulationIsAssociative() {
        var random = new Random(20260813L);

        for (int trial = 0; trial < 200; trial++) {
            var a = reportOf(random(random, random.nextInt(20)));
            var b = reportOf(random(random, random.nextInt(20)));
            var c = reportOf(random(random, random.nextInt(20)));

            assertThat(a.plus(b).plus(c)).isEqualTo(a.plus(b.plus(c)));
        }
    }

    @Test
    @DisplayName("walked never exceeds the runs that produced evidence")
    void walkedNeverExceedsEvidence() {
        var random = new Random(20260814L);

        for (int trial = 0; trial < TRIALS; trial++) {
            var campaign = random(random, random.nextInt(40));
            var report = reportOf(campaign);

            long evidence = campaign.outcomes().stream().filter(RunOutcome::isEvidence).count();
            // Only a run that reached its state and produced a result can prove the path,
            // so walked counts a subset of the evidence and never more.
            assertThat(report.walked()).isLessThanOrEqualTo((int) evidence);
            assertThat(report.walked()).isLessThanOrEqualTo(report.total());
        }
    }

    @Test
    @DisplayName("a campaign with no runs claims nothing")
    void anEmptyCampaignClaimsNothing() {
        var empty = CampaignReport.of(List.of(), List.of());

        assertThat(empty.total()).isZero();
        assertThat(empty.passRate()).isZero();
        // Nothing ran, so there is nothing to trust and no path was proved.
        assertThat(empty.isTrustworthy()).isFalse();
        assertThat(empty.provesAnyReachability()).isFalse();
    }

    @Test
    @DisplayName("the pass rate stays inside zero and one")
    void thePassRateIsAProportion() {
        var random = new Random(20260815L);

        for (int trial = 0; trial < TRIALS; trial++) {
            assertThat(reportOf(random(random, random.nextInt(40))).passRate())
                .isBetween(0.0, 1.0);
        }
    }

    @Test
    @DisplayName("one outcome per precursor, or the report is refused")
    void mismatchedLengthsAreRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                CampaignReport.of(List.of(new RunOutcome.Unscoreable("x")), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("one precursor per outcome");
    }
}
