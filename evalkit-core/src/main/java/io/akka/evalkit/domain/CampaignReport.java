package io.akka.evalkit.domain;

import java.util.List;

/**
 * What a campaign is allowed to claim.
 *
 * <p>The temptation in every eval report is one number. This refuses to produce one,
 * because the runs behind it are not all the same kind of thing: some were judged, some
 * never reached the state they were meant to test, some the judge would not answer, and
 * some got a score that does not reproduce. A single percentage has to pick one lie to tell
 * about the rest.
 *
 * @param passed      judged FAITHFUL
 * @param review      judged PARTIAL — undecided, not weak; see {@link Band#needsReview()}
 * @param failed      judged NO_MATCH
 * @param notReached  the precursor never landed. Says nothing about the system
 * @param unscoreable the judge did not answer. Also says nothing about the system
 * @param scorerFailed the scorer broke. Says nothing about the system either, and unlike the
 *                     two above it is a defect in this kit rather than a property of the run
 * @param asserted    settled by comparison against a named decision, with no model call
 * @param assertedPassed of those, how many reached the decision they named
 * @param measured    settled by a metric computing a number and comparing it to a threshold
 * @param measuredPassed of those, how many came in within the threshold
 * @param walked      of the judged runs, how many proved the path rather than seeding it
 * @param setupFailed of the not-reached, how many stopped before anything was asked
 * @param noReply     of the not-reached, how many were asked and did not answer
 */
public record CampaignReport(int passed, int review, int failed,
                             int notReached, int unscoreable, int walked, int asserted,
                             int assertedPassed, int measured, int measuredPassed,
                             int setupFailed, int noReply, int scorerFailed) {

    /** A report from before scorer failures were counted separately, which reads them as none. */
    public CampaignReport(int passed, int review, int failed,
                          int notReached, int unscoreable, int walked, int asserted,
                          int assertedPassed, int measured, int measuredPassed,
                          int setupFailed, int noReply) {
        this(passed, review, failed, notReached, unscoreable, walked, asserted, assertedPassed,
            measured, measuredPassed, setupFailed, noReply, 0);
    }

    /**
     * A deterministic run has two outcomes, never three.
     *
     * <p>Comparison against a named decision either matches or does not; there is no
     * confidence to be borderline about. Undecided is a property of a model's score, so
     * a report showing an undecided deterministic run is a report with a bug in it, and
     * the two populations are printed in separate columns so that stays visible.
     */
    public int assertedFailed() {
        return asserted - assertedPassed;
    }

    /** Of the measured runs, how many missed their threshold. */
    public int measuredFailed() {
        return measured - measuredPassed;
    }

    public int scoredPassed() {
        return passed - assertedPassed - measuredPassed;
    }

    public int scoredFailed() {
        return failed - assertedFailed() - measuredFailed();
    }

    public static CampaignReport of(List<RunOutcome> outcomes, List<Precursor> precursors) {
        if (outcomes.size() != precursors.size()) {
            throw new IllegalArgumentException("one precursor per outcome");
        }
        int passed = 0, review = 0, failed = 0, notReached = 0, unscoreable = 0, walked = 0;
        int asserted = 0, assertedPassed = 0, measured = 0, measuredPassed = 0;
        int setupFailed = 0, noReply = 0, scorerFailed = 0;
        for (int i = 0; i < outcomes.size(); i++) {
            RunOutcome outcome = outcomes.get(i);
            switch (outcome) {
                case RunOutcome.Scored s -> {
                    if (s.verdict().band().passed()) passed++;
                    else if (s.verdict().band().needsReview()) review++;
                    else failed++;
                    if (precursors.get(i).provesReachability()) walked++;
                }
                case RunOutcome.Asserted a -> {
                    asserted++;
                    if (a.passed()) {
                        passed++;
                        assertedPassed++;
                    } else {
                        failed++;
                    }
                    if (precursors.get(i).provesReachability()) walked++;
                }
                case RunOutcome.Measured m -> {
                    measured++;
                    if (m.withinThreshold()) {
                        passed++;
                        measuredPassed++;
                    } else {
                        failed++;
                    }
                    if (precursors.get(i).provesReachability()) walked++;
                }
                case RunOutcome.NotReached n -> {
                    notReached++;
                    if (n.cause() == RunOutcome.Cause.SETUP_FAILED) setupFailed++;
                    else noReply++;
                }
                case RunOutcome.Unscoreable ignored -> unscoreable++;
                case RunOutcome.ScorerFailed ignored -> scorerFailed++;
            }
        }
        return new CampaignReport(passed, review, failed, notReached, unscoreable, walked,
            asserted, assertedPassed, measured, measuredPassed, setupFailed, noReply,
            scorerFailed);
    }

    public static CampaignReport empty() {
        return new CampaignReport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Two reports added.
     *
     * <p>A long campaign is processed in waves so it can survive a restart, and the
     * running total is what the workflow carries between them — keeping every individual
     * outcome in workflow state would serialise thousands of transcripts on every
     * transition.
     */
    public CampaignReport plus(CampaignReport other) {
        return new CampaignReport(
            passed + other.passed, review + other.review, failed + other.failed,
            notReached + other.notReached, unscoreable + other.unscoreable,
            walked + other.walked, asserted + other.asserted,
            assertedPassed + other.assertedPassed,
            measured + other.measured, measuredPassed + other.measuredPassed,
            setupFailed + other.setupFailed, noReply + other.noReply,
            scorerFailed + other.scorerFailed);
    }

    /** Runs that produced evidence about the system. */
    public int judged() {
        return passed + review + failed;
    }

    public int total() {
        return judged() + withoutEvidence();
    }

    /**
     * Pass rate over judged runs only.
     *
     * <p>Over {@link #judged()} rather than {@link #total()} deliberately: a harness that
     * failed to reach half its states would otherwise report a halved pass rate and look
     * like a product regression. {@link #isTrustworthy()} is what stops that denominator
     * from being quietly flattering.
     */
    public double passRate() {
        return judged() == 0 ? 0 : (double) passed / judged();
    }

    /**
     * Whether the pass rate is worth quoting.
     *
     * <p>Two ways it is not. If a tenth of the runs never produced evidence, the campaign
     * measured itself more than the system. If a fifth of the judged runs are undecided,
     * the rubric is not discriminating and the pass rate is an artefact of where the
     * band boundary happens to fall.
     */
    public boolean isTrustworthy() {
        if (total() == 0) return false;
        boolean enoughEvidence = (double) withoutEvidence() / total() <= 0.10;
        boolean enoughAgreement = judged() > 0 && (double) review / judged() <= 0.20;
        return enoughEvidence && enoughAgreement;
    }

    /**
     * Whether any judged run proved a path rather than seeding past it.
     *
     * <p>Seeding is the throughput win; it is also how a suite stops noticing that a state
     * has become unreachable. A campaign that is entirely seeded is fast and cannot tell
     * you the conversation still works.
     */
    public boolean provesAnyReachability() {
        return walked > 0;
    }

    /** Runs that produced no evidence, whatever the reason. */
    public int withoutEvidence() {
        return notReached + unscoreable + scorerFailed;
    }

    /** Runs that cost a model call. Comparison and computation settled the rest. */
    public int scored() {
        return judged() - asserted - measured;
    }

    public String summary() {
        var caveats = new StringBuilder();
        if (!provesAnyReachability() && judged() > 0) {
            caveats.append("  [entirely seeded — no path was walked]");
        }
        if (!isTrustworthy()) {
            caveats.append("  [pass rate not trustworthy]");
        }
        return ("%d judged of %d (%d asserted, %d scored) — %d passed, %d need review, "
            + "%d failed; %d not reached, %d unscoreable, %d scorer failures%s")
            .formatted(judged(), total(), asserted, scored(), passed, review, failed,
                notReached, unscoreable, scorerFailed, caveats);
    }
}
