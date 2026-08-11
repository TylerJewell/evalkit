package io.akka.evalkit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every check here has a corpus it is known to refuse.
 *
 * <p>A pre-flight check that can pass by finding nothing looks identical to a corpus with
 * nothing wrong with it, so each case below is a scenario the check must reject and a
 * neighbouring one it must let through.
 */
@DisplayName("A corpus that cannot be evaluated is refused before anything is spent")
class CorpusDefectTest {

    private static final Rubric RUBRIC = Rubric.load("scenario-judge", 3);

    private record Anything() implements SystemUnderTest {
        @Override
        public Prepared prepare(Precursor precursor) {
            return new Prepared.Ready("s1", "");
        }

        @Override
        public Reply submit(String sessionId, String userText) {
            return Reply.of("said something");
        }

        @Override
        public Map<String, String> fixtures() {
            return Map.of();
        }
    }

    private static Scenario ok(String id) {
        return new Scenario(id, Optional.empty(), new Precursor.None(),
            "what if they don't fit?", "a 30-day refund at no extra cost");
    }

    private static List<String> refusalReasons(Scenario... scenarios) {
        var check = new CampaignPlan("c", List.of(scenarios), Lanes.of(1), RUBRIC)
            .check(new Anything());
        return check instanceof CampaignPlan.Check.Refused refused ? refused.reasons() : List.of();
    }

    @Test
    @DisplayName("two scenarios sharing an id are refused, because each is counted twice")
    void duplicateIdsAreRefused() {
        assertThat(refusalReasons(ok("refund-30d"), ok("refund-30d")))
            .anySatisfy(reason -> assertThat(reason)
                .contains("share the ids [refund-30d]")
                .contains("counted twice"));
    }

    @Test
    @DisplayName("distinct ids pass")
    void distinctIdsPass() {
        assertThat(refusalReasons(ok("refund-30d"), ok("refund-14d"))).isEmpty();
    }

    @Test
    @DisplayName("required wording the scenario's own outcome forbids is refused")
    void selfContradictingWordingIsRefused() {
        // No reply satisfies both, so this fails on every run for every service and reads
        // as a system that never complies.
        var contradictory = new Scenario("no-fee", Optional.empty(), Optional.empty(),
            List.of("a fee"), new Precursor.None(),
            "will I be charged?", "states the refund is free and must not a fee");

        assertThat(refusalReasons(contradictory))
            .anySatisfy(reason -> assertThat(reason)
                .contains("require wording their own expected outcome says must not appear"));
    }

    @Test
    @DisplayName("required wording an outcome merely mentions is allowed")
    void wordingThatIsMerelyMentionedPasses() {
        var fine = new Scenario("fee-stated", Optional.empty(), Optional.empty(),
            List.of("no extra cost"), new Precursor.None(),
            "will I be charged?", "states the refund is at no extra cost");

        assertThat(refusalReasons(fine)).isEmpty();
    }

    @Test
    @DisplayName("a scenario expecting back exactly what it said is refused")
    void unfilledTemplateIsRefused() {
        // The shape a corpus takes when a template was filled in halfway. It passes or
        // fails for reasons nobody chose.
        var placeholder = new Scenario("todo-1", Optional.empty(), new Precursor.None(),
            "TODO", "TODO");

        assertThat(refusalReasons(placeholder))
            .anySatisfy(reason -> assertThat(reason)
                .contains("expect back exactly what they said"));
    }

    @Test
    @DisplayName("a scenario whose expectation differs from its turn passes")
    void aRealScenarioPasses() {
        assertThat(refusalReasons(ok("refund-30d"))).isEmpty();
    }

    @Test
    @DisplayName("every reason a corpus is refused names the scenarios at fault")
    void reasonsNameTheScenarios() {
        var reasons = refusalReasons(ok("dup"), ok("dup"),
            new Scenario("todo-1", Optional.empty(), new Precursor.None(), "TODO", "TODO"));

        // A refusal a reader cannot act on sends them to grep the corpus.
        assertThat(reasons).hasSize(2);
        assertThat(reasons).allSatisfy(reason ->
            assertThat(reason).containsAnyOf("dup", "todo-1"));
    }
}
