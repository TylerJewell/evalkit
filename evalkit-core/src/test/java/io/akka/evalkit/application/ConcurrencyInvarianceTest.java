package io.akka.evalkit.application;

import io.akka.evalkit.domain.CampaignPlan;
import io.akka.evalkit.domain.CampaignReport;
import io.akka.evalkit.domain.Lanes;
import io.akka.evalkit.domain.Precursor;
import io.akka.evalkit.domain.Observation;
import io.akka.evalkit.domain.RunOutcome;
import io.akka.evalkit.domain.Rubric;
import io.akka.evalkit.domain.Scenario;
import io.akka.evalkit.domain.Scorer;
import io.akka.evalkit.domain.ScorerRouter;
import io.akka.evalkit.domain.SystemUnderTest;
import io.akka.evalkit.domain.Grade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the lane count is not allowed to change.
 *
 * <p>Lanes decide how many scenarios run at once and nothing else. A report that moves
 * with the lane count is reporting the harness, and this repository has recorded that
 * happening twice: a campaign that returned 53 outcomes for 80 scenarios because a thrown
 * exception was parked in a future nobody read, and a per-scenario row that carried
 * another requirement's detail because outcomes and scenarios were zipped by index while
 * arriving in completion order.
 */
@DisplayName("CampaignRunner · the lane count changes throughput and nothing else")
class ConcurrencyInvarianceTest {

    private static final Rubric RUBRIC = new Rubric("scenario-judge", 2,
        "{replay_history}{simulation_history}{system_output}{expected_outcome}");

    /** Answers the node the scenario asked about, so a mispairing is visible. */
    private static final class Target implements SystemUnderTest {
        private final boolean jitter;

        Target(boolean jitter) {
            this.jitter = jitter;
        }

        @Override
        public Prepared prepare(Precursor precursor) {
            return new Prepared.Ready("s", "");
        }

        @Override
        public Reply submit(String sessionId, String userText) {
            if (jitter) {
                // Uneven work is what makes workers interleave. A campaign whose replies
                // all take the same time hides an ordering bug.
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(2));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // The user text carries the scenario id, so the answer names its own scenario.
            return Reply.from("answered " + userText, "node-" + userText);
        }

        @Override
        public Map<String, String> fixtures() {
            return Map.of("ready", "a prepared state");
        }
    }

    /** Scores every observation, naming the scenario it was given. */
    private static final class NamingJudge implements Scorer {
        private final int failEvery;

        NamingJudge(int failEvery) {
            this.failEvery = failEvery;
        }

        @Override
        public RunOutcome score(Observation observation) {
            String name = observation.scenarioName();
            if (failEvery > 0 && Math.abs(name.hashCode()) % failEvery == 0) {
                throw new IllegalStateException("judge refused " + name);
            }
            return new RunOutcome.Scored(Grade.of(name, RUBRIC, 9, ""));
        }
    }

    private static Scenario judged(String id) {
        return new Scenario(id, Optional.empty(), Optional.empty(),
            Precursor.Fixture.named("ready"), id, "an answer naming " + id);
    }

    private static Scenario asserted(String id) {
        return new Scenario(id, Optional.of("node-" + id), Optional.empty(),
            Precursor.Fixture.named("ready"), id, "reaches node-" + id);
    }

    private static List<Scenario> dataset(int size) {
        var out = new ArrayList<Scenario>();
        for (int i = 0; i < size; i++) {
            out.add(i % 2 == 0 ? judged("s" + i) : asserted("s" + i));
        }
        return out;
    }

    private static CampaignReport runAt(int lanes, List<Scenario> scenarios) {
        var plan = new CampaignPlan("invariance", scenarios, Lanes.of(lanes), RUBRIC);
        return CampaignRunner.run(plan, new Target(false),
            ScorerRouter.judgingEverything(new NamingJudge(0))).report();
    }

    @ParameterizedTest(name = "{0} lanes produce the same report as one lane")
    @ValueSource(ints = {2, 4, 8, 16, 64})
    @DisplayName("the report does not move with the lane count")
    void theReportIsInvariantAcrossLanes(int lanes) {
        var scenarios = dataset(200);

        var sequential = runAt(1, scenarios);
        var parallel = runAt(lanes, scenarios);

        // CampaignReport is a record, so this compares every count at once. A new column
        // added without wiring would show up here as well as in its own test.
        assertThat(parallel).isEqualTo(sequential);
    }

    @Test
    @DisplayName("every scenario produces exactly one row")
    void everyScenarioProducesOneRow() {
        var scenarios = dataset(400);
        var plan = new CampaignPlan("invariance", scenarios, Lanes.of(16), RUBRIC);

        var result = CampaignRunner.run(plan, new Target(true),
            ScorerRouter.judgingEverything(new NamingJudge(3)));

        assertThat(result.completed()).hasSize(400);
        assertThat(result.completed().stream().map(c -> c.scenario().id()).distinct())
            .hasSize(400);
    }

    @Test
    @DisplayName("each outcome belongs to the scenario it is filed against")
    void outcomesStayWithTheirScenario() {
        var scenarios = dataset(400);
        var plan = new CampaignPlan("invariance", scenarios, Lanes.of(16), RUBRIC);

        var result = CampaignRunner.run(plan, new Target(true),
            ScorerRouter.judgingEverything(new NamingJudge(0)));

        // Each outcome names the scenario the target was asked about. Submission order and
        // completion order differ under 16 lanes, so a row assembled by index would fail
        // here on most runs and pass on some, which is how the original bug survived.
        for (CampaignRunner.Completed completed : result.completed()) {
            String id = completed.scenario().id();
            switch (completed.outcome()) {
                case RunOutcome.Scored s -> assertThat(s.grade().scenarioName()).isEqualTo(id);
                case RunOutcome.Asserted a -> assertThat(a.expected()).isEqualTo("node-" + id);
                default -> throw new AssertionError(
                    "this dataset produces only scored and asserted rows, got " + completed.outcome());
            }
        }
    }

    @Test
    @DisplayName("a scorer that throws costs its own row and no other")
    void aThrowingScorerLosesNothing() {
        var scenarios = dataset(400);
        var plan = new CampaignPlan("invariance", scenarios, Lanes.of(16), RUBRIC);

        var result = CampaignRunner.run(plan, new Target(true),
            ScorerRouter.judgingEverything(new NamingJudge(3)));
        var report = result.report();

        assertThat(result.completed()).hasSize(400);
        assertThat(report.total()).isEqualTo(400);
        // The throws land in one column and nowhere else, so the count still adds up.
        assertThat(report.judged() + report.withoutEvidence()).isEqualTo(400);
        assertThat(report.scorerFailed()).isPositive();
    }

    @Test
    @DisplayName("running the same campaign twice produces the same report")
    void theSameCampaignRepeats() {
        var scenarios = dataset(200);

        assertThat(runAt(8, scenarios)).isEqualTo(runAt(8, scenarios));
    }
}
