package io.akka.evalkit.application;

import io.akka.evalkit.domain.CampaignPlan;
import io.akka.evalkit.domain.Lanes;
import io.akka.evalkit.domain.Panels;
import io.akka.evalkit.domain.Precursor;
import io.akka.evalkit.domain.RequirementResult;
import io.akka.evalkit.domain.Rubric;
import io.akka.evalkit.domain.RunOutcome;
import io.akka.evalkit.domain.Scenario;
import io.akka.evalkit.domain.ScorerRouter;
import io.akka.evalkit.domain.SystemUnderTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("A campaign that runs each requirement more than once")
class RepeatedCampaignTest {

    private static final Rubric RUBRIC = Rubric.load("scenario-judge", 3);

    /** A target whose answer for a named scenario changes from run to run. */
    private static final class Wobbly implements SystemUnderTest {
        private final Map<String, AtomicInteger> seen = new ConcurrentHashMap<>();
        private final String flaky;

        Wobbly(String flaky) {
            this.flaky = flaky;
        }

        @Override
        public Prepared prepare(Precursor precursor) {
            return new Prepared.Ready("session-" + System.nanoTime(), "");
        }

        @Override
        public Reply submit(String sessionId, String userText) {
            String scenario = userText;
            int n = seen.computeIfAbsent(scenario, k -> new AtomicInteger()).getAndIncrement();
            String node = scenario.equals(flaky) && n % 2 == 1 ? "WRONG" : "GenUC-1";
            return new Reply("answered", Optional.of(node),
                Optional.of(java.time.Duration.ofSeconds(2)), List.of());
        }

        @Override
        public Map<String, String> fixtures() {
            return Map.of();
        }
    }

    private static Scenario scenario(String id) {
        return new Scenario(id, Optional.of("GenUC-1"), new Precursor.None(), id, "the answer");
    }

    private static CampaignPlan plan(int repeats) {
        return plan(repeats, 2);
    }

    private static CampaignPlan plan(int repeats, int lanes) {
        return new CampaignPlan("repeats",
            List.of(scenario("steady"), scenario("flaky")), Lanes.of(lanes), RUBRIC)
            .repeating(repeats);
    }

    private static ScorerRouter router() {
        return ScorerRouter.judgingEverything(
            recording -> new RunOutcome.Unscoreable("no judge in this campaign"));
    }

    @Test
    @DisplayName("each scenario runs the configured number of times")
    void everyScenarioRunsEveryTime() {
        var result = CampaignRunner.run(plan(5), new Wobbly("flaky"), router());

        assertThat(result.completed()).hasSize(10);
        assertThat(result.requirements()).hasSize(2);
        assertThat(result.requirements()).allSatisfy(r ->
            assertThat(r.runCount()).isEqualTo(5));
    }

    @Test
    @DisplayName("a scenario that answers differently between runs is varied, not passed")
    void aFlakyScenarioIsVaried() {
        // Under one run this scenario passes and the report calls the system correct.
        var result = CampaignRunner.run(plan(5), new Wobbly("flaky"), router());

        var byId = result.requirements().stream()
            .collect(java.util.stream.Collectors.toMap(RequirementResult::id, r -> r));

        assertThat(byId.get("steady").verdict()).isEqualTo(RequirementResult.Verdict.PASSED);
        assertThat(byId.get("flaky").verdict()).isEqualTo(RequirementResult.Verdict.VARIED);
        assertThat(byId.get("flaky").passes()).isEqualTo(3);
    }

    @Test
    @DisplayName("one run hides what five runs reveal")
    void oneRunHidesIt() {
        var once = CampaignRunner.run(plan(1), new Wobbly("flaky"), router());

        // The first run of the flaky scenario passes, so a single run reports it met.
        assertThat(once.requirements()).allSatisfy(r ->
            assertThat(r.verdict()).isEqualTo(RequirementResult.Verdict.PASSED));
    }

    @Test
    @DisplayName("the report counts requirements while the tally counts runs")
    void requirementsAndRunsAreCountedApart() {
        var result = CampaignRunner.run(plan(5), new Wobbly("flaky"), router());

        // Ten rows were produced and two requirements were tested. Reading the tally as
        // requirements would report a corpus five times the size of the one that ran.
        assertThat(result.report().total()).isEqualTo(10);
        assertThat(result.requirements()).hasSize(2);
    }

    @Test
    @DisplayName("a requirement's marks are in the order the runs were started")
    void runsKeepTheirOrder() {
        // One lane, so this target's alternating answer lines up with the submission
        // order and the expected sequence is a fact rather than a race.
        var result = CampaignRunner.run(plan(6, 1), new Wobbly("flaky"), router());
        var flaky = result.requirements().stream()
            .filter(r -> r.id().equals("flaky")).findFirst().orElseThrow();

        // Workers append as they finish and runs of one scenario are indistinguishable
        // once appended, so gathering by list order would show the marks in whatever
        // sequence the lanes produced.
        assertThat(Panels.strip(flaky).marks()).isEqualTo("+ - + - + -");
    }

    @Test
    @DisplayName("the same requirement gives the same marks however many lanes ran it")
    void marksDoNotDependOnLaneCount() {
        // Across lanes the runs finish out of order. Sorting by the attempt they were
        // submitted as is what keeps the sequence the report prints a real one.
        var oneLane = CampaignRunner.run(plan(8, 1), new Wobbly("flaky"), router());
        var manyLanes = CampaignRunner.run(plan(8, 4), new Wobbly("flaky"), router());

        assertThat(passCount(oneLane)).isEqualTo(passCount(manyLanes));
        assertThat(Panels.strip(flakyOf(oneLane)).marks()).isEqualTo("+ - + - + - + -");
    }

    private static int passCount(CampaignRunner.Result result) {
        return flakyOf(result).passes();
    }

    private static RequirementResult flakyOf(CampaignRunner.Result result) {
        return result.requirements().stream()
            .filter(r -> r.id().equals("flaky")).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the plan states how many runs it will cost before anything is spent")
    void planStatesItsCost() {
        assertThat(plan(5).runs()).isEqualTo(10);
        assertThat(plan(1).runs()).isEqualTo(2);
        assertThatThrownBy(() -> plan(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("runs each scenario once");
    }
}
