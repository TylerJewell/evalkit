package io.akka.evalkit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A run that is counted and never printed reads as a clean campaign.
 *
 * <p>{@link CampaignReport} holds a column for every outcome family. Whether the printed
 * report shows them is a separate question, and a family that reaches the counts but not
 * the page leaves a reader believing a campaign went better than it did.
 */
@DisplayName("RunSummary · every family a campaign produced reaches the page")
class RenderingCoverageTest {

    private static final RunSummary.Identity IDENTITY = new RunSummary.Identity(
        "Refund conformance test", "CH-2026-08-05-002", "16f0085");

    private static final RunSummary.Coverage COVERAGE = new RunSummary.Coverage(
        List.of(new RunSummary.Journey("refunds", 40)), List.of());

    private static String flat(String text) {
        return text.replaceAll("\\s+", " ");
    }

    /** A report with a value in every column, so nothing can pass by being zero. */
    private static CampaignReport everyFamily() {
        return new CampaignReport(
            30,  // passed
            4,   // review
            9,   // failed
            5,   // notReached
            2,   // inconclusive
            11,  // walked
            20,  // asserted
            17,  // assertedPassed
            10,  // measured
            8,   // measuredPassed
            3,   // setupFailed
            2);  // noReply
    }

    private static String render(CampaignReport report) {
        return flat(RunSummary.results(IDENTITY, report, COVERAGE, List.of(), 45,
            "scenarios.csv", null));
    }

    @Test
    @DisplayName("the measured column appears in the results table")
    void theMeasuredColumnIsPrinted() {
        var text = render(everyFamily());

        assertThat(text).contains("measured");
        // 8 measured passed and 2 measured failed, in the columns beside the other families.
        assertThat(text).contains("checked by rules").contains("judged").contains("total");
    }

    @Test
    @DisplayName("both columns that cannot be undecided print a dash rather than a zero")
    void twoColumnsCarryADash() {
        var text = render(everyFamily());

        // A zero in either cell would read as a finding about the service. A dash says the
        // question does not arise, which is what a comparison and a threshold both mean.
        int dashes = text.split("undecided", 2)[1].split("no result", 2)[0].split("-", -1).length - 1;
        assertThat(dashes).isEqualTo(2);
    }

    @Test
    @DisplayName("every run with no result is broken out by its reason")
    void noResultIsBrokenOut() {
        var text = render(everyFamily());

        assertThat(text).contains("never reached the question");
        assertThat(text).contains("no reply within 45 seconds");
        assertThat(text).contains("answer not assessed");
    }

    @Test
    @DisplayName("a campaign with no result at all says so instead of printing an empty breakdown")
    void aCleanRunSaysSo() {
        var clean = new CampaignReport(60, 0, 0, 0, 0, 20, 30, 30, 10, 10, 0, 0);

        assertThat(render(clean)).contains("Every test produced a result.");
    }

    @Test
    @DisplayName("a campaign that produced no evidence refuses to print a pass rate")
    void anUntrustworthyRunWithholdsItsRate() {
        // Half the runs produced nothing, so a pass rate over the rest would describe the
        // harness. The report says that in words rather than printing a flattering number.
        var thin = new CampaignReport(10, 0, 0, 8, 2, 5, 10, 10, 0, 0, 6, 2);

        var text = render(thin);

        assertThat(thin.isTrustworthy()).isFalse();
        assertThat(text).contains("reports no overall pass rate");
    }

    @Test
    @DisplayName("a failing run reaches the findings list with what happened to it")
    void findingsCarryWhatHappened() {
        var findings = List.of(
            new RunSummary.Finding("GenUC-16a.3", "expected GenUC-16a.3, reached GenUC-17a"),
            new RunSummary.Finding("refund-30d", "tool-permission v1: 0.50 against 1.00"));

        var text = flat(RunSummary.results(IDENTITY, everyFamily(), COVERAGE, findings, 45,
            "scenarios.csv", null));

        assertThat(text).contains("GenUC-16a.3").contains("reached GenUC-17a");
        // A measured failure has to be readable as a row, not only countable as a column.
        assertThat(text).contains("tool-permission v1");
    }

    @Test
    @DisplayName("every outcome's own description is printable as a finding")
    void everyDescriptionIsPrintable() {
        // The report prints three findings and points at the file for the rest, so each
        // variant is checked in a run of its own rather than competing for the three rows.
        List<RunOutcome> everyVariant = List.of(
            new RunOutcome.Asserted(false, "GenUC-1", "GenUC-2"),
            new RunOutcome.Measured("tool-permission", 1, 0.5, 1.0, false),
            new RunOutcome.NotReached(RunOutcome.Cause.SETUP_FAILED, "no fixture",
                new Precursor.None()),
            new RunOutcome.Inconclusive("the content filter refused"));

        for (RunOutcome outcome : everyVariant) {
            var text = flat(RunSummary.results(IDENTITY, everyFamily(), COVERAGE,
                List.of(new RunSummary.Finding("req", outcome.describe())), 45,
                "scenarios.csv", null));

            assertThat(text)
                .as("description of " + outcome.getClass().getSimpleName())
                .contains(flat(outcome.describe()));
        }
    }

    @Test
    @DisplayName("the findings list stops at three rows and names the file holding the rest")
    void findingsTruncateAndPointAtTheFile() {
        var findings = List.of(
            new RunSummary.Finding("r1", "expected GenUC-1, reached GenUC-2"),
            new RunSummary.Finding("r2", "tool-permission v1: 0.50 against 1.00"),
            new RunSummary.Finding("r3", "NO_MATCH (3/10) under scenario-judge v2"),
            new RunSummary.Finding("r4", "inconclusive — the content filter refused"));

        var text = flat(RunSummary.results(IDENTITY, everyFamily(), COVERAGE, findings, 45,
            "findings.csv", null));

        assertThat(text).contains("r1").contains("r2").contains("r3");
        // A reader who needs the fourth is told where it is rather than left to wonder
        // whether the report simply stopped.
        assertThat(text).contains("more in findings.csv");
    }

    @Test
    @DisplayName("the report still fits a terminal with every column carrying a value")
    void everyLineFitsWithAllColumnsFilled() {
        var findings = List.of(new RunSummary.Finding("GenUC-16a.3",
            "tool-permission v1: 0.50 against 1.00"));

        RunSummary.results(IDENTITY, everyFamily(), COVERAGE, findings, 45, "scenarios.csv", null)
            .lines()
            .forEach(line -> assertThat(line.length())
                .as("line runs past the frame: " + line)
                .isLessThanOrEqualTo(80));
    }
}
