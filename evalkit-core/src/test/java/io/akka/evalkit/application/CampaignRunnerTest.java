package io.akka.evalkit.application;

import io.akka.evalkit.domain.CampaignPlan;
import io.akka.evalkit.domain.Lanes;
import io.akka.evalkit.domain.Precursor;
import io.akka.evalkit.domain.RunOutcome;
import io.akka.evalkit.domain.Rubric;
import io.akka.evalkit.domain.Scenario;
import io.akka.evalkit.domain.SystemUnderTest;
import io.akka.evalkit.domain.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CampaignRunner · running a plan and refusing to overclaim")
class CampaignRunnerTest {

    private static final Rubric RUBRIC = new Rubric("scenario-judge", 2,
        "{replay_history}{simulation_history}{system_output}{expected_outcome}");

    private static final class Target implements SystemUnderTest {
        final java.util.Map<String, String> fixtures;
        String answer = "the payment will be made via Interac";
        String node = "GenUC-17a";

        Target(String... fixtures) {
            var m = new java.util.LinkedHashMap<String, String>();
            for (String f : fixtures) m.put(f, "a prepared state");
            this.fixtures = java.util.Map.copyOf(m);
        }

        @Override
        public Prepared prepare(Precursor precursor) {
            return new Prepared.Ready("s", precursor.provesReachability() ? "User: hi\n\nAgent: ok" : "");
        }

        @Override
        public Reply submit(String sessionId, String userText) {
            return Reply.from(answer, node);
        }

        @Override
        public java.util.Map<String, String> fixtures() {
            return fixtures;
        }
    }

    /** A scenario with no spec node, so it needs a judge. */
    private static Scenario judged(String id, Precursor precursor) {
        return new Scenario(id, Optional.empty(), precursor, "how do I claim?",
            "The agent should explain how to claim.");
    }

    private static CampaignPlan plan(List<Scenario> scenarios, int lanes) {
        return new CampaignPlan("c1", scenarios, Lanes.of(lanes), RUBRIC);
    }

    private static CampaignRunner.Judge scoring(int score) {
        return (transcript, rubric) ->
            new RunOutcome.Scored(Verdict.of(transcript.scenarioName(), rubric, score, ""));
    }

    // ---- pre-flight ----

    @Test
    @DisplayName("a plan naming fixtures the target lacks is refused before it runs")
    void refusesMissingFixtures() {
        var check = plan(List.of(judged("a", Precursor.Fixture.named("nope"))), 2)
            .check(new Target("authenticated"));

        assertThat(check).isInstanceOfSatisfying(CampaignPlan.Check.Refused.class,
            r -> assertThat(r.reasons()).anyMatch(s -> s.contains("nope")));
    }

    @Test
    @DisplayName("a plan where every scenario is a decision runs, and calls no model")
    void allAssertedIsAValidCampaign() {
        // The cheapest campaign there is: every scenario settled by comparison, no model
        // call, no variance. Refusing it would reject the case this kit exists to make.
        var asserted = new Scenario("GenUC-17a", Optional.of("GenUC-17a"),
            new Precursor.None(), "cash please", "Interac");

        assertThat(plan(List.of(asserted), 2).check(new Target()))
            .isInstanceOf(CampaignPlan.Check.Ready.class);
    }

    @Test
    @DisplayName("a workable plan is ready, and says what it will and will not prove")
    void readyPlan() {
        var p = plan(List.of(
            judged("a", Precursor.Fixture.named("authenticated")),
            judged("b", new Precursor.Replay(List.of("hi")))), 4);

        assertThat(p.check(new Target("authenticated"))).isInstanceOf(CampaignPlan.Check.Ready.class);
        assertThat(p.judged()).isEqualTo(2);
        assertThat(p.walked()).isEqualTo(1);
        assertThat(p.summary()).contains("4 lanes");
    }

    @Test
    @DisplayName("a wholly seeded plan says so before it runs")
    void seededPlanWarnsUpFront() {
        var p = plan(List.of(judged("a", Precursor.Fixture.named("authenticated"))), 2);

        assertThat(p.summary()).contains("cannot detect an unreachable state");
    }

    // ---- running ----

    @Test
    @DisplayName("every scenario produces an outcome, across lanes")
    void runsEverything() {
        var scenarios = new ArrayList<Scenario>();
        for (int i = 0; i < 40; i++) {
            scenarios.add(judged("s" + i, Precursor.Fixture.named("authenticated")));
        }

        var result = CampaignRunner.run(plan(scenarios, 8), new Target("authenticated"), scoring(9));

        assertThat(result.outcomes()).hasSize(40);
        assertThat(result.report().passed()).isEqualTo(40);
        assertThat(result.report().judged()).isEqualTo(40);
    }

    @Test
    @DisplayName("a judge that throws becomes absent evidence, not a failure")
    void judgeRefusal() {
        // Gemini's content filter did exactly this during calibration.
        CampaignRunner.Judge refusing = (t, r) -> {
            throw new IllegalStateException("content filter refused the transcript");
        };

        var result = CampaignRunner.run(
            plan(List.of(judged("a", Precursor.Fixture.named("authenticated"))), 1),
            new Target("authenticated"), refusing);

        assertThat(result.report().unscoreable()).isEqualTo(1);
        assertThat(result.report().failed()).isZero();
        assertThat(result.outcomes()).first().isInstanceOfSatisfying(RunOutcome.Unscoreable.class,
            u -> assertThat(u.reason()).contains("content filter"));
    }

    @Test
    @DisplayName("a target that says nothing is not reached, not judged badly")
    void silentTarget() {
        var target = new Target("authenticated");
        target.answer = "";

        var result = CampaignRunner.run(
            plan(List.of(judged("a", Precursor.Fixture.named("authenticated"))), 1),
            target, scoring(9));

        assertThat(result.report().notReached()).isEqualTo(1);
        assertThat(result.report().judged()).isZero();
    }

    // ---- what it refuses to claim ----

    @Test
    @DisplayName("a seeded campaign is told it cannot detect an unreachable state")
    void notesSeeding() {
        var scenarios = new ArrayList<Scenario>();
        for (int i = 0; i < 20; i++) {
            scenarios.add(judged("s" + i, Precursor.Fixture.named("authenticated")));
        }

        var result = CampaignRunner.run(plan(scenarios, 4), new Target("authenticated"), scoring(9));

        assertThat(result.notes()).anyMatch(n -> n.contains("entirely seeded"));
    }

    @Test
    @DisplayName("an undecided campaign is told its pass rate is not quotable")
    void notesUntrustworthy() {
        var scenarios = new ArrayList<Scenario>();
        for (int i = 0; i < 10; i++) {
            scenarios.add(judged("s" + i, Precursor.Fixture.named("authenticated")));
        }

        // Everything lands in PARTIAL, where two judges agreed only 53% of the time.
        var result = CampaignRunner.run(plan(scenarios, 4), new Target("authenticated"), scoring(5));

        assertThat(result.report().isTrustworthy()).isFalse();
        assertThat(result.notes()).anyMatch(n -> n.contains("not quotable"));
    }

    @Test
    @DisplayName("utilisation reports what was sustained, and which way to fix it")
    void utilisation() {
        var scenarios = new ArrayList<Scenario>();
        for (int i = 0; i < 12; i++) {
            scenarios.add(judged("s" + i, Precursor.Fixture.named("authenticated")));
        }

        var result = CampaignRunner.run(plan(scenarios, 4), new Target("authenticated"), scoring(9));

        assertThat(result.utilisation().configured()).isEqualTo(4);
        assertThat(result.utilisation().completed()).isEqualTo(12);
        assertThat(result.utilisation().achieved()).isGreaterThan(0);
        // The remedy differs depending on which side the constraint sits, and the two
        // are indistinguishable from throughput alone.
        assertThat(result.utilisation().summary())
            .containsAnyOf("more lanes would help", "more lanes would just queue");
    }

    @Test
    @DisplayName("a scenario naming a decision is settled by comparison, with no model call")
    void assertedScenariosSkipTheJudge() {
        // The routing this harness exists for. 510 of 514 claim scenarios are this kind,
        // so judging them would be most of the cost of a campaign, spent on things that
        // have a right answer.
        var judgeCalls = new java.util.concurrent.atomic.AtomicInteger();
        CampaignRunner.Judge counting = (t, r) -> {
            judgeCalls.incrementAndGet();
            return new RunOutcome.Scored(Verdict.of(t.scenarioName(), r, 9, ""));
        };

        var asserted = new Scenario("GenUC-17a: cash", Optional.of("GenUC-17a"),
            Precursor.Fixture.named("authenticated"), "cash please",
            "The agent should say Interac.");

        var result = CampaignRunner.run(
            plan(List.of(asserted, judged("faq", Precursor.Fixture.named("authenticated"))), 2),
            new Target("authenticated"), counting);

        assertThat(judgeCalls.get()).isEqualTo(1);
        assertThat(result.report().asserted()).isEqualTo(1);
        assertThat(result.report().scored()).isEqualTo(1);
        assertThat(result.report().passed()).isEqualTo(2);
    }

    @Test
    @DisplayName("a decision that reached the wrong node fails without a model saying so")
    void assertedMismatch() {
        var target = new Target("authenticated");
        target.node = "GenUC-27";

        var scenario = new Scenario("GenUC-17a: cash", Optional.of("GenUC-17a"),
            Precursor.Fixture.named("authenticated"), "cash please", "Interac.");

        var result = CampaignRunner.run(plan(List.of(scenario), 1), target, scoring(9));

        assertThat(result.report().failed()).isEqualTo(1);
        assertThat(result.report().asserted()).isEqualTo(1);
        assertThat(result.outcomes()).first().isInstanceOfSatisfying(RunOutcome.Asserted.class,
            a -> assertThat(a.actualNode()).isEqualTo("GenUC-27"));
    }

    @Test
    @DisplayName("lane counts are bounded at both ends")
    void laneBounds() {
        assertThat(Lanes.of(1).configured()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Lanes.of(0))
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Lanes.of(5000))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
