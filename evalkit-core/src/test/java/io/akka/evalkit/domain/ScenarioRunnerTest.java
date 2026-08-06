package io.akka.evalkit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ScenarioRunner · reaching a state and grading one turn")
class ScenarioRunnerTest {

    /** A target that records what was asked of it, so cost can be counted. */
    private static final class FakeTarget implements SystemUnderTest {
        final List<String> submitted = new ArrayList<>();
        final java.util.Map<String, String> fixtures;
        String failPrepare;
        String answer = "here is your answer";
        String node = "GenUC-17a";

        FakeTarget(String... fixtures) {
            var m = new java.util.LinkedHashMap<String, String>();
            for (String f : fixtures) m.put(f, "a prepared state");
            this.fixtures = java.util.Map.copyOf(m);
        }

        @Override
        public Prepared prepare(Precursor precursor) {
            if (failPrepare != null) return new Prepared.Failed(failPrepare);
            return switch (precursor) {
                case Precursor.None ignored -> new Prepared.Ready("s1", "");
                // Walking the front door costs one call per recorded turn.
                case Precursor.Replay r -> {
                    var history = new StringBuilder();
                    for (String turn : r.userTurns()) {
                        submitted.add(turn);
                        history.append("User: ").append(turn).append("\n\nAgent: ok\n\n");
                    }
                    yield new Prepared.Ready("s1", history.toString().strip());
                }
                // Seeding costs none.
                case Precursor.Fixture f -> new Prepared.Ready("s1", "");
            };
        }

        @Override
        public Reply submit(String sessionId, String userText) {
            submitted.add(userText);
            return Reply.from(answer, node);
        }

        @Override
        public java.util.Map<String, String> fixtures() {
            return fixtures;
        }
    }

    private static Scenario scenario(Precursor precursor) {
        return new Scenario("GenUC-17a: cash payment", Optional.of("GenUC-17a"), precursor,
            "I would like cash please",
            "The agent should inform the user that payment will be made via Interac.");
    }

    @Test
    @DisplayName("a seeded run reaches the state without saying anything")
    void fixtureCostsNoTurns() {
        var target = new FakeTarget("authenticated-with-claim");
        var result = ScenarioRunner.execute(
            scenario(Precursor.Fixture.named("authenticated-with-claim")), target);

        assertThat(result).isInstanceOf(ScenarioRunner.Execution.Produced.class);
        // One call: the graded turn. This is the whole argument for the fixture path.
        assertThat(target.submitted).containsExactly("I would like cash please");
    }

    @Test
    @DisplayName("the same scenario walked costs one call per recorded turn")
    void replayCostsEveryTurn() {
        var target = new FakeTarget();
        var setup = List.of("I want to claim", "ABC123", "123456", "the first one");

        var result = ScenarioRunner.execute(
            scenario(new Precursor.Replay(setup)), target);

        assertThat(result).isInstanceOf(ScenarioRunner.Execution.Produced.class);
        assertThat(target.submitted).hasSize(setup.size() + 1);
        // Five calls against one — the throughput claim, made concrete. Claim corpora
        // average ten setup exchanges, so the real ratio is worse than this.
        assertThat(target.submitted).hasSizeGreaterThan(4);
    }

    @Test
    @DisplayName("a seeded transcript shows no history, because nothing was said")
    void seededHistoryIsEmpty() {
        var target = new FakeTarget("authenticated");
        var result = (ScenarioRunner.Execution.Produced) ScenarioRunner.execute(
            scenario(Precursor.Fixture.named("authenticated")), target);

        assertThat(result.transcript().hasSetup()).isFalse();
        assertThat(result.transcript().simulationHistory()).contains("I would like cash please");
        assertThat(result.transcript().systemOutput()).isEqualTo("here is your answer");
    }

    @Test
    @DisplayName("a walked transcript carries the setup for the judge to read")
    void walkedHistoryIsCarried() {
        var target = new FakeTarget();
        var result = (ScenarioRunner.Execution.Produced) ScenarioRunner.execute(
            scenario(new Precursor.Replay(List.of("I want to claim", "ABC123"))), target);

        // The rubric interpolates replay_history; dropping it would judge the graded turn
        // with the context that makes it make sense removed.
        assertThat(result.transcript().hasSetup()).isTrue();
        assertThat(result.transcript().replayHistory()).contains("ABC123");
    }

    @Test
    @DisplayName("an unknown fixture is refused before anything is said")
    void unknownFixture() {
        var target = new FakeTarget("authenticated");
        var result = ScenarioRunner.execute(
            scenario(Precursor.Fixture.named("claim-in-triage")), target);

        assertThat(result).isInstanceOfSatisfying(ScenarioRunner.Execution.NotReached.class,
            n -> assertThat(n.reason()).contains("claim-in-triage").contains("authenticated"));
        assertThat(target.submitted).isEmpty();
    }

    @Test
    @DisplayName("a campaign naming fixtures the target lacks is knowable before it starts")
    void unsupportedUpFront() {
        // The alternative is discovering it after forty minutes of not-reached results.
        var target = new FakeTarget("authenticated");
        var missing = ScenarioRunner.unsupported(List.of(
            scenario(Precursor.Fixture.named("claim-in-triage")),
            scenario(Precursor.Fixture.named("authenticated")),
            scenario(Precursor.Fixture.named("bag-delivered"))), target);

        assertThat(missing).containsExactly("bag-delivered", "claim-in-triage");
    }

    @Test
    @DisplayName("setup that fails is not reached, and names the precursor")
    void prepareFailed() {
        var target = new FakeTarget("authenticated");
        target.failPrepare = "OTP service timed out";

        var result = ScenarioRunner.execute(
            scenario(Precursor.Fixture.named("authenticated")), target);

        assertThat(result).isInstanceOfSatisfying(ScenarioRunner.Execution.NotReached.class,
            n -> assertThat(n.reason()).contains("fixture authenticated").contains("OTP service"));
    }

    @Test
    @DisplayName("a silent system is not reached rather than scored badly")
    void emptyAnswer() {
        // Grading emptiness produces a low score that reads as a bad reply.
        var target = new FakeTarget("authenticated");
        target.answer = "  ";

        var result = ScenarioRunner.execute(
            scenario(Precursor.Fixture.named("authenticated")), target);

        assertThat(result).isInstanceOfSatisfying(ScenarioRunner.Execution.NotReached.class,
            n -> assertThat(n.reason()).contains("returned nothing"));
    }

    @Test
    @DisplayName("a scenario naming a spec node needs no judge")
    void judgeRouting() {
        assertThat(scenario(new Precursor.None()).needsJudge()).isFalse();
        assertThat(new Scenario("FAQ-01459 What gift am I getting?", Optional.empty(),
            new Precursor.None(), "what gift", "the agent should ask what programme")
            .needsJudge()).isTrue();
    }
}
