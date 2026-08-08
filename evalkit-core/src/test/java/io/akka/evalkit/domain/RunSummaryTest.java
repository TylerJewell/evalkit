package io.akka.evalkit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RunSummary · what a run prints")
class RunSummaryTest {

    private static final RunSummary.Identity IDENTITY = new RunSummary.Identity(
        "Claim conformance test", "AC-2026-08-05-001", "16f0085");

    private static final RunSummary.Coverage COVERAGE = new RunSummary.Coverage(
        List.of(new RunSummary.Journey("flight disruption", 160),
                new RunSummary.Journey("standards of care", 156),
                new RunSummary.Journey("baggage", 110),
                new RunSummary.Journey("general enquiries", 88),
                new RunSummary.Journey("non-standard requests", 40)),
        List.of(new RunSummary.Journey("booking changes", 14),
                new RunSummary.Journey("seat fees", 7)));

    private static final RunSummary.JudgeProfile JUDGE = new RunSummary.JudgeProfile(
        "scenario-judge", 2, "28 July", "target/eval/rubric-scenario-judge-v2.txt",
        89, 91, 53, "Those 44 are all general enquiries.");

    private static final Map<String, String> DESCRIPTIONS = Map.of(
        "authenticated-with-claim", "identity verified, one claim open",
        "awaiting-answer", "as above, plus a question just asked",
        "authenticated", "identity verified",
        "fresh", "nothing configured");

    private static Map<String, Integer> use() {
        var m = new LinkedHashMap<String, Integer>();
        m.put("authenticated-with-claim", 312);
        m.put("awaiting-answer", 181);
        m.put("authenticated", 48);
        m.put("fresh", 13);
        return m;
    }

    /** 470 passed, 12 failed, 8 undecided, 64 with no result split 41/16/7. */
    private static CampaignReport report() {
        return new CampaignReport(470, 8, 12, 57, 7, 0, 510, 462, 0, 0, 41, 16);
    }

    private static final RunSummary.Estimate ESTIMATE =
        new RunSummary.Estimate(2900, 1900, 554, 44);

    private static final RunSummary.Spend SPEND =
        new RunSummary.Spend(new Tokens(1_284_300, 216_400),
                             new Tokens(297_100, 98_200), 0);

    private static String opening() {
        return RunSummary.opening(IDENTITY, COVERAGE, 510, 44, JUDGE,
            DESCRIPTIONS, use(), "docs/eval/scenarios.jsonl", ESTIMATE);
    }

    private static String results() {
        return RunSummary.results(report(), COVERAGE, List.of(
            new RunSummary.Finding("GenUC-17b",
                "American customers were offered Interac, a Canadian service"),
            new RunSummary.Finding("SoC-03e",
                "receipts were collected once, not per partner airline"),
            new RunSummary.Finding("BAG-02a",
                "a claim filed after 21 days was accepted, not declined")),
            90, "target/eval/findings.csv", SPEND);
    }

    /** Wrapped prose read as one line, so an assertion is about words not line breaks. */
    private static String flat(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    // ---- what the opening block must say ----

    @Test
    @DisplayName("the opening states what is not tested, not only what is")
    void openingNamesExclusions() {
        // A summary listing only what ran invites a reader to assume the rest passed.
        assertThat(flat(opening()))
            .contains("It does not test booking changes and seat fees, 21 requirements");
    }

    @Test
    @DisplayName("the opening separates what is checked from what is judged")
    void openingSeparatesRulesFromJudgement() {
        assertThat(flat(opening()))
            .contains("510 of the 554 are checked against fixed rules")
            .contains("The other 44 produce free text");
    }

    @Test
    @DisplayName("the bands are printed with the agreement that justifies them")
    void openingPrintsBandsAndAgreement() {
        // The 4-7 band counting as undecided is the most contestable choice in the
        // report, so the measurement behind it appears beside it rather than in a note.
        assertThat(flat(opening()))
            .contains("4-7 matches in part counted undecided")
            .contains("89-91% of the time on clear-cut replies and 53%")
            .contains("never as a pass");
    }

    @Test
    @DisplayName("starting states are named and described, and the file is given")
    void openingExplainsStartingStates() {
        assertThat(flat(opening()))
            .contains("authenticated-with-claim")
            .contains("identity verified, one claim open")
            .contains("docs/eval/scenarios.jsonl")
            .contains("doesn't prove that a user can reach that point unaided");
    }

    @Test
    @DisplayName("the rubric is identified by version, so two runs can be compared")
    void openingIdentifiesTheRubric() {
        assertThat(flat(opening())).contains("Rubric scenario-judge v2, unchanged since 28 July");
    }

    // ---- what the results block must say ----

    @Test
    @DisplayName("the headline gives four numbers, never one")
    void resultsGiveFourOutcomes() {
        assertThat(flat(results())).contains(
            "470 of 554 requirements behaved as specified, 12 did not, 8 were too "
                + "borderline to call and 64 produced no result");
    }

    @Test
    @DisplayName("no result is broken down by cause")
    void resultsDecomposeNonResults() {
        // "64 produced no result" on its own reads as a fault nobody can act on.
        assertThat(flat(results()))
            .contains("never reached the question")
            .contains("no reply within 90 seconds")
            .contains("answer not assessed");
    }

    @Test
    @DisplayName("each cause is explained in a reader's terms")
    void resultsExplainEachCause() {
        assertThat(flat(results()))
            .contains("The 41 stopped before the system was asked anything")
            .contains("The 16 were asked and did not answer within 90 seconds")
            .contains("The 7 were answered, but the second model would not judge");
    }

    @Test
    @DisplayName("a run with unobserved requirements quotes no pass rate")
    void resultsWithholdThePassRate() {
        assertThat(flat(results()))
            .contains("This run therefore reports no overall pass rate")
            .contains("The 12 findings below stand");
    }

    @Test
    @DisplayName("three findings are shown and the rest are pointed to")
    void resultsShowTopFindings() {
        assertThat(flat(results()))
            .contains("GenUC-17b")
            .contains("9 more in target/eval/findings.csv");
    }

    @Test
    @DisplayName("exclusions are repeated at the end, where the numbers are")
    void resultsRepeatExclusions() {
        // In a scrolling log the opening block is long gone by the time results print.
        assertThat(flat(results())).contains("Booking changes and seat fees remain untested");
    }

    // ---- layout rules ----

    @Test
    @DisplayName("a count above zero always draws at least one block")
    void smallCountsStayVisible() {
        // 12 of 554 rounds to nothing at this width. A finding that disappears because
        // it is small is exactly what this summary exists to prevent.
        assertThat(RunSummary.blocks(12, 554)).isEqualTo(1);
        assertThat(RunSummary.blocks(1, 100000)).isEqualTo(1);
        assertThat(RunSummary.blocks(0, 554)).isZero();
    }

    @Test
    @DisplayName("nothing runs past the frame")
    void everyLineFitsTheTerminal() {
        // Build output is read in an 80-column terminal, often behind a Maven prefix.
        for (String line : (opening() + results()).split("\n")) {
            assertThat(line.length())
                .as("line runs past the rule: %s", line)
                .isLessThanOrEqualTo(72);
        }
    }

    @Test
    @DisplayName("plain ASCII only, so logs and tickets survive it")
    void asciiOnly() {
        // Box-drawing and block glyphs do not survive being piped to a file, captured by
        // CI, or pasted into a ticket.
        assertThat((opening() + results()).chars().allMatch(c -> c < 128))
            .as("summary contains a character outside ASCII")
            .isTrue();
    }

    @Test
    @DisplayName("a clean run says so rather than printing an empty breakdown")
    void cleanRun() {
        var clean = new CampaignReport(554, 0, 0, 0, 0, 0, 510, 510, 0, 0, 0, 0);
        var text = flat(RunSummary.results(clean, COVERAGE, List.of(), 90, "x.csv", SPEND));

        assertThat(text).contains("Every test produced a result.");
        assertThat(text).doesNotContain("reports no overall pass rate");
    }

    @Test
    @DisplayName("the opening states expected cost, and the arithmetic behind it")
    void estimatesCost() {
        var text = flat(opening());

        // 2900*554 + 1900*44 = 1,690,200, rounded to two significant figures.
        assertThat(text).contains("Expected model usage: about 1,700,000 tokens");
        assertThat(text).contains("554 conversations at 2,900 each");
        assertThat(text).contains("44 judgements at 1,900");
    }

    @Test
    @DisplayName("the closing splits what the system spent from what the judge spent")
    void reportsSpend() {
        var text = flat(results());

        assertThat(text).contains("Model usage: 1,896,000 tokens, 1,581,400 in and 314,600 out");
        assertThat(text).contains("the system under test 1,284,300 in 216,400 out");
        assertThat(text).contains("the judge 297,100 in 98,200 out");
        assertThat(text).doesNotContain("floor rather than a measurement");
    }

    @Test
    @DisplayName("an unaccounted model call makes the total a floor, not a measurement")
    void unaccountedCallsAreVisible() {
        var partial = new RunSummary.Spend(new Tokens(1000, 200), new Tokens(500, 100), 3);
        var text = flat(RunSummary.results(report(), COVERAGE, List.of(), 90, "x.csv", partial));

        assertThat(text).contains("three model replies carried no usage figure");
        assertThat(text).contains("floor rather than a measurement");
    }
}
