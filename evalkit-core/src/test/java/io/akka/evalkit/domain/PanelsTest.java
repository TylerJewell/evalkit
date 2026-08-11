package io.akka.evalkit.domain;

import io.akka.evalkit.metric.MetricRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Panels · the report rendered from a run record")
class PanelsTest {

    private static final Rubric RUBRIC = Rubric.load("scenario-judge", 3);

    private static final List<String> MEASURES = List.of(
        "specification node", "scenario judge", "required wording", "task completion",
        "tool permission", "tool correctness", "argument correctness", "turn faithfulness",
        "citation faithfulness", "turn relevancy", "plan quality", "plan adherence",
        "step efficiency");

    private static final RunSummary.JudgeProfile JUDGE = new RunSummary.JudgeProfile(
        "scenario-judge", 3, "28 July", "rubrics/scenario-judge-v3.txt", 89, 91, 53,
        "Those are enquiries.");

    // ---- fixtures ----

    private static Scenario node(String id) {
        return new Scenario(id, Optional.of("GenUC-16a"), new Precursor.None(),
            "what if they don't fit?", "a 30-day refund");
    }

    private static Scenario judged(String id) {
        return new Scenario(id, Optional.empty(), new Precursor.None(), "turn", "outcome");
    }

    private static Scenario metered(String id, String metric) {
        return new Scenario(id, Optional.empty(), Optional.of(new MetricRef(metric, 1)),
            new Precursor.None(), "turn", "outcome");
    }

    private static RunOutcome asserted(boolean passed) {
        return new RunOutcome.Asserted(passed, "GenUC-16a", passed ? "GenUC-16a" : "GenUC-17a");
    }

    private static RequirementResult result(Scenario scenario, Duration latency,
                                            RunOutcome... outcomes) {
        var runs = new ArrayList<RequirementResult.Run>();
        for (RunOutcome outcome : outcomes) {
            runs.add(new RequirementResult.Run(outcome, Optional.ofNullable(latency)));
        }
        return new RequirementResult(scenario, runs);
    }

    /** A single-run campaign: four passes, one failure, one undecided, one with no result. */
    private static RunRecord singleRun() {
        var requirements = List.of(
            result(node("refund-30d"), Duration.ofSeconds(3), asserted(true)),
            result(node("refund-14d"), Duration.ofSeconds(4), asserted(false)),
            result(metered("tool-scope", "tool-permission"), Duration.ofSeconds(6),
                new RunOutcome.Measured("tool-permission", 1, 0.5, 1.0, false)),
            result(judged("interac-offer"), Duration.ofSeconds(40),
                new RunOutcome.Scored(Verdict.of("interac-offer", RUBRIC, 9, "clear"))),
            result(judged("cash-country"), Duration.ofSeconds(50),
                new RunOutcome.Scored(Verdict.of("cash-country", RUBRIC, 5, "borderline"))),
            result(judged("escalation"), null,
                new RunOutcome.NotReached(RunOutcome.Cause.NO_REPLY, "timed out",
                    new Precursor.None())));

        return new RunRecord(
            new RunSummary.Identity("Refund policy evaluation", "2026-08-11T09:14Z",
                "claims-svc 4.2.0"),
            Optional.of(new Policy("refund-desk", 3, "Refunds within 30 days.")),
            JUDGE, 4, 45, requirements, MEASURES,
            new RunSummary.Coverage(List.of(new RunSummary.Journey("refunds", 6)),
                List.of(new RunSummary.Journey("booking changes", 14))),
            new RunSummary.Spend(new Tokens(70_000, 4_000), new Tokens(7_628, 948), 0),
            "target/evalkit/refund-policy.jsonl");
    }

    /** The same corpus with five runs each, so the varied panel has something to show. */
    private static RunRecord repeated(int repeats) {
        var requirements = new ArrayList<RequirementResult>();
        requirements.add(result(node("steady"), Duration.ofSeconds(2),
            outcomes(repeats, i -> asserted(true))));
        requirements.add(result(node("flaky"), Duration.ofSeconds(2),
            outcomes(repeats, i -> asserted(i % 2 == 0))));
        requirements.add(result(node("broken"), Duration.ofSeconds(2),
            outcomes(repeats, i -> asserted(false))));

        return new RunRecord(
            new RunSummary.Identity("Repeats", "R-2", "svc 1.0"), Optional.empty(),
            JUDGE, 4, 45, requirements, MEASURES,
            new RunSummary.Coverage(List.of(new RunSummary.Journey("refunds", 3)), List.of()),
            new RunSummary.Spend(new Tokens(10, 5), new Tokens(1, 1), 0), "x.jsonl");
    }

    private static RunOutcome[] outcomes(int n, java.util.function.IntFunction<RunOutcome> f) {
        var all = new RunOutcome[n];
        for (int i = 0; i < n; i++) all[i] = f.apply(i);
        return all;
    }

    // ---- what the report must always hold ----

    @Test
    @DisplayName("nothing runs past the frame, at any size")
    void everyLineFitsTheTerminal() {
        for (RunRecord record : List.of(singleRun(), repeated(5), repeated(80))) {
            for (String line : Panels.render(record).split("\n")) {
                assertThat(line.length())
                    .as("line runs past the frame: %s", line)
                    .isLessThanOrEqualTo(80);
            }
        }
    }

    @Test
    @DisplayName("plain ASCII only, so logs and tickets survive it")
    void asciiOnly() {
        String text = Panels.render(singleRun()) + Panels.render(repeated(5));
        assertThat(text.chars().allMatch(c -> c < 128)).isTrue();
    }

    @Test
    @DisplayName("the first panel accounts for every requirement, and no more")
    void firstPanelReconciles() {
        var record = singleRun();
        int counted = record.byVerdict().values().stream().mapToInt(Integer::intValue).sum();

        assertThat(counted).isEqualTo(record.requirementCount());
    }

    @Test
    @DisplayName("the quality panel accounts for every requirement, and no more")
    void qualityPanelReconciles() {
        var record = singleRun();
        int counted = record.byMeasure().values().stream()
            .flatMap(row -> row.values().stream())
            .mapToInt(Integer::intValue).sum();

        assertThat(counted).isEqualTo(record.requirementCount());
    }

    @Test
    @DisplayName("a measure nothing used is a row at zero, not an absence")
    void unusedMeasuresStillAppear() {
        var text = Panels.render(singleRun());

        assertThat(text).contains("plan adherence").contains("citation faithfulness");
        assertThat(record(text, "plan adherence")).endsWith("0");
    }

    // ---- panels appear when they have something to say ----

    @Test
    @DisplayName("a single run prints no varied panel, and numbers the rest without gaps")
    void singleRunHasNoVariedPanel() {
        var text = Panels.render(singleRun());

        assertThat(text).doesNotContain("gave different answers");
        assertThat(text).contains("1  What the run found");
        assertThat(text).contains("2  What failed");
        assertThat(text).contains("3  How quality was measured");
        assertThat(text).contains("4  How the judge scored");
        assertThat(text).contains("5  What this run cannot tell you");
        assertThat(text).contains("6  What it cost");
    }

    @Test
    @DisplayName("repeats add the varied panel, and everything after it renumbers")
    void repeatsAddTheVariedPanel() {
        var text = Panels.render(repeated(5));

        assertThat(text).contains("3  The requirements that gave different answers");
        assertThat(text).contains("4  How quality was measured");
        assertThat(text).contains("flaky").contains("3 of 5");
    }

    @Test
    @DisplayName("a campaign nobody judged prints no score distribution")
    void noJudgeNoHistogram() {
        assertThat(Panels.render(repeated(5))).doesNotContain("How the judge scored");
    }

    // ---- the rules that hold at any scale ----

    @Test
    @DisplayName("the run strip stays one column wide however many runs there were")
    void stripIsFixedWidth() {
        var five = Panels.strip(repeated(5).requirements().get(1));
        var eighty = Panels.strip(repeated(80).requirements().get(1));

        assertThat(five.slice()).isEqualTo(1);
        assertThat(five.marks()).isEqualTo("+ - + - +");
        assertThat(eighty.slice()).isEqualTo(4);
        assertThat(eighty.marks()).hasSize(20);
        assertThat(eighty.marks()).doesNotContain(" ");
    }

    @Test
    @DisplayName("the legend says what a mark covers once a mark covers more than one run")
    void stripLegendFollowsTheSlice() {
        assertThat(flat(Panels.render(repeated(5)))).contains("Each mark is one run");
        assertThat(flat(Panels.render(repeated(80))))
            .contains("Each mark covers 4 runs")
            .contains("more than half of those 4 passed");
    }

    @Test
    @DisplayName("the floor a clean sweep establishes is the exact bound, not an approximation")
    void confidenceFloorIsExact() {
        // 0.55^5 is about 5%, so five clean runs rule out nothing worse than 55%. A normal
        // approximation reads 57% here, which overstates by two points at the extreme.
        assertThat(Panels.floor(5)).isEqualTo(55);
        assertThat(Panels.floor(20)).isEqualTo(86);
        assertThat(Panels.floor(50)).isEqualTo(94);
    }

    @Test
    @DisplayName("the confidence note recomputes for the repeat count, rather than quoting five")
    void confidenceFollowsTheRepeatCount() {
        assertThat(Panels.confidence(1)).contains("One run cannot tell");
        assertThat(Panels.confidence(5)).contains("anywhere from 55% to 100%");
        assertThat(Panels.confidence(20)).contains("anywhere from 86% to 100%");
        // Past a point a clean sweep is not something an unreliable requirement does,
        // and the illustration stops illustrating.
        assertThat(Panels.confidence(80)).doesNotContain("8 times in 10");
    }

    @Test
    @DisplayName("a target reporting no timings says so rather than drawing an empty shape")
    void missingLatencyIsStated() {
        var record = repeated(5);   // built with a latency on every run
        var withoutTimings = new RunRecord(record.identity(), record.policy(), record.judge(),
            record.lanes(), record.replyTimeoutSeconds(),
            record.requirements().stream()
                .map(r -> new RequirementResult(r.scenario(), r.runs().stream()
                    .map(run -> new RequirementResult.Run(run.outcome())).toList()))
                .toList(),
            record.measures(), record.coverage(), record.spend(), record.recordPath());

        assertThat(flat(Panels.render(withoutTimings))).contains("The target reported no timings");
        assertThat(Panels.render(record)).contains("How long the system took to answer");
    }

    @Test
    @DisplayName("the heading names the policy and the record the figures came from")
    void headingCarriesProvenance() {
        var text = Panels.render(singleRun());

        assertThat(flat(text)).contains("rules refund-desk v3");
        assertThat(flat(text)).contains("record target/evalkit/refund-policy.jsonl");
        assertThat(flat(text)).contains("scope 6 requirements, 6 runs");
        assertThat(flat(text)).contains("rubric scenario-judge v3");
    }

    /** Wrapped prose read as one line, so an assertion is about words not line breaks. */
    private static String flat(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static String record(String text, String label) {
        return text.lines().filter(l -> l.contains(label)).findFirst().orElseThrow().strip();
    }
}
