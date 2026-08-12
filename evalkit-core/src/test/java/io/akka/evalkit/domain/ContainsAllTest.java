package io.akka.evalkit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ContainsAll · whether the reply stated what the policy requires")
class ContainsAllTest {

    private static Observation replying(String systemOutput) {
        return Observation.of(new Transcript("refund-30d", "", "user: what if they don't fit?",
            systemOutput, "a 30-day refund at no extra cost"));
    }

    /** The user asking about a term is not the system stating it. */
    private static Observation userSaidItInstead(String simulationHistory) {
        return Observation.of(new Transcript("refund-30d", "", simulationHistory,
            "Let me look into that for you.", "a 30-day refund at no extra cost"));
    }

    @Test
    @DisplayName("a reply carrying every phrase passes")
    void allPresent() {
        var outcome = ContainsAll.of("30 days", "no extra cost")
            .score(replying("You have 30 days to return them, at no extra cost."));

        assertThat(outcome).isInstanceOf(RunOutcome.Asserted.class);
        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.describe()).contains("all 2 required phrases");
    }

    @Test
    @DisplayName("a missing phrase fails and the finding names it")
    void oneMissing() {
        var outcome = ContainsAll.of("30 days", "no extra cost")
            .score(replying("You have 30 days to return them."));

        assertThat(outcome.passed()).isFalse();
        // A reader has to be able to act on the row without opening the transcript.
        assertThat(outcome.describe()).contains("no extra cost").contains("a reply without it");
    }

    @Test
    @DisplayName("matching ignores case and line breaks")
    void toleratesCaseAndWhitespace() {
        var outcome = ContainsAll.of("no extra cost")
            .score(replying("Returned within 30 days, at\n   NO EXTRA   cost."));

        assertThat(outcome.passed()).isTrue();
    }

    @Test
    @DisplayName("paraphrase does not pass")
    void paraphraseFails() {
        // A comparison that accepted this would pass every reply that omits the term,
        // which is the whole of what this checks.
        var outcome = ContainsAll.of("no extra cost").score(replying("It's free of charge."));

        assertThat(outcome.passed()).isFalse();
    }

    @Test
    @DisplayName("only the reply is searched, never what the user said")
    void doesNotMatchTheUsersOwnWords() {
        // The phrase appears in the graded exchange because the user used it. Searching
        // there would report the system as having stated something it never said.
        var outcome = ContainsAll.of("no extra cost")
            .score(userSaidItInstead("user: is it at no extra cost?"));

        assertThat(outcome.passed()).isFalse();
    }

    @Test
    @DisplayName("a target reporting no reply text gives no verdict, not a failure")
    void noReplyTextIsUnscoreable() {
        // Blaming the system for evidence the harness never received is the error this
        // whole kit exists to avoid.
        var outcome = ContainsAll.of("no extra cost")
            .score(Observation.of(new Transcript("refund-30d", "", "user: hello?", "  ",
                "a 30-day refund")));

        assertThat(outcome).isInstanceOf(RunOutcome.Inconclusive.class);
        assertThat(outcome.isEvidence()).isFalse();
    }

    @Test
    @DisplayName("a scorer with nothing to look for is refused at construction")
    void emptyIsRefused() {
        // It would pass every reply, and the report would show a requirement as checked.
        assertThatThrownBy(() -> ContainsAll.of(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no required phrases");

        assertThatThrownBy(() -> ContainsAll.of("30 days", "  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("blank");
    }

    @Test
    @DisplayName("a scenario stating required phrases routes to the comparison, not the judge")
    void routesWithoutAModel() {
        var scenario = new Scenario("refund-30d", java.util.Optional.empty(),
            new Precursor.None(), "what if they don't fit?", "a 30-day refund")
            .requiring("30 days", "no extra cost");

        assertThat(scenario.needsJudge()).isFalse();

        Scorer judge = observation -> {
            throw new AssertionError("a judge was called for a decision with a right answer");
        };
        var scorer = ScorerRouter.judgingEverything(judge).scorerFor(scenario).orElseThrow();

        assertThat(scorer.id()).isEqualTo("contains-all");
        assertThat(scorer.score(replying("30 days, no extra cost.")).passed()).isTrue();
    }

    @Test
    @DisplayName("a scenario cannot state two kinds of expectation")
    void oneExpectationPerScenario() {
        assertThatThrownBy(() -> new Scenario("refund-30d",
            java.util.Optional.of("GenUC-1"), java.util.Optional.empty(),
            List.of("30 days"), new Precursor.None(), "turn", "outcome"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("more than one expectation");
    }
}
