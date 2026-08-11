package io.akka.evalkit.domain;

import io.akka.evalkit.metric.MetricRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RequirementResult · what a requirement did across its runs")
class RequirementResultTest {

    private static final Rubric RUBRIC = Rubric.load("scenario-judge", 3);

    private static Scenario scenario() {
        return new Scenario("refund-30d", Optional.of("GenUC-16a"), new Precursor.None(),
            "what if they don't fit?", "a 30-day refund");
    }

    private static RequirementResult over(RunOutcome... outcomes) {
        return new RequirementResult(scenario(),
            java.util.Arrays.stream(outcomes).map(RequirementResult.Run::new).toList());
    }

    private static RunOutcome asserted(boolean passed) {
        return new RunOutcome.Asserted(passed, "GenUC-16a", passed ? "GenUC-16a" : "GenUC-17a");
    }

    private static RunOutcome scored(int score) {
        return new RunOutcome.Scored(Verdict.of("refund-30d", RUBRIC, score, "because"));
    }

    @Test
    @DisplayName("every run passing is the only thing counted as passed")
    void allRunsMustPass() {
        assertThat(over(asserted(true), asserted(true), asserted(true)).verdict())
            .isEqualTo(RequirementResult.Verdict.PASSED);
    }

    @Test
    @DisplayName("one failing run makes a requirement varied, not passed")
    void oneFailureIsEnoughToVary() {
        // Reporting this as a pass would claim a property four of five runs cannot show.
        var result = over(asserted(true), asserted(true), asserted(false),
            asserted(true), asserted(true));

        assertThat(result.verdict()).isEqualTo(RequirementResult.Verdict.VARIED);
        assertThat(result.passes()).isEqualTo(4);
        assertThat(result.runCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("every run failing is a failure")
    void allRunsFailing() {
        assertThat(over(asserted(false), asserted(false)).verdict())
            .isEqualTo(RequirementResult.Verdict.FAILED);
    }

    @Test
    @DisplayName("a run that produced nothing is not a failure")
    void noEvidenceIsNotAFailure() {
        // The system was never asked, so nothing here is a finding about it.
        var result = over(new RunOutcome.NotReached(RunOutcome.Cause.SETUP_FAILED,
            "no fixture", new Precursor.None()));

        assertThat(result.verdict()).isEqualTo(RequirementResult.Verdict.NO_RESULT);
        assertThat(result.passes()).isZero();
    }

    @Test
    @DisplayName("a judge that would not commit leaves the requirement undecided")
    void middleBandIsUndecided() {
        assertThat(over(scored(5), scored(6)).verdict())
            .isEqualTo(RequirementResult.Verdict.UNDECIDED);
    }

    @Test
    @DisplayName("runs that settled decide the verdict, and runs that produced nothing do not")
    void decidedRunsCarryTheVerdict() {
        // Four passes and one run the harness never completed is not a varied requirement:
        // varying is the system answering differently, not the harness falling over.
        var result = new RequirementResult(scenario(), List.of(
            new RequirementResult.Run(asserted(true)),
            new RequirementResult.Run(asserted(true)),
            new RequirementResult.Run(new RunOutcome.Unscoreable("the filter refused")),
            new RequirementResult.Run(asserted(true))));

        assertThat(result.verdict()).isEqualTo(RequirementResult.Verdict.PASSED);
    }

    @Test
    @DisplayName("the measure is read from what the scenario declares")
    void measureFollowsTheScenario() {
        assertThat(over(asserted(true)).measure()).isEqualTo("specification node");

        var wording = new RequirementResult(
            new Scenario("no-fee", Optional.empty(), new Precursor.None(), "turn", "outcome")
                .requiring("no extra cost"),
            List.of(new RequirementResult.Run(asserted(true))));
        assertThat(wording.measure()).isEqualTo("required wording");

        var metric = new RequirementResult(
            new Scenario("tools", Optional.empty(), Optional.of(new MetricRef("tool-permission", 1)),
                new Precursor.None(), "turn", "outcome"),
            List.of(new RequirementResult.Run(asserted(true))));
        assertThat(metric.measure()).isEqualTo("tool permission");

        var judged = new RequirementResult(
            new Scenario("prose", Optional.empty(), new Precursor.None(), "turn", "outcome"),
            List.of(new RequirementResult.Run(scored(9))));
        assertThat(judged.measure()).isEqualTo("scenario judge");
    }

    @Test
    @DisplayName("a failing requirement describes itself with the run that failed")
    void describesTheFailingRun() {
        var result = over(asserted(true), asserted(false));

        assertThat(result.describe()).contains("expected GenUC-16a, found GenUC-17a");
    }

    @Test
    @DisplayName("a requirement with no runs is refused")
    void noRunsIsRefused() {
        // Every place it could be counted would be a claim about a system never asked.
        assertThatThrownBy(() -> new RequirementResult(scenario(), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("has no runs");
    }
}
