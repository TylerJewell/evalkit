package io.akka.evalkit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EndState · whether the run left the world as the scenario expected")
class EndStateTest {

    private static Observation leaving(Map<String, String> state) {
        var transcript = new Transcript("refund-30d", "", "user: refund these please",
            "Done, that's refunded.", "the refund is issued");
        return new Observation(transcript,
            io.akka.evalkit.ledger.Interactions.of("", "", "Done, that's refunded.",
                List.of(), Optional.empty(), Optional.empty()),
            Optional.empty(), state);
    }

    @Test
    @DisplayName("the expected keys matching is a pass, whatever else the target reports")
    void extraStateDoesNotFail() {
        // A target reports whatever it can see, and most of it has nothing to do with the
        // scenario. Requiring an exact match would fail every scenario the first time the
        // target reported one more field.
        var outcome = EndState.matching("refund.status", "issued")
            .score(leaving(Map.of("refund.status", "issued", "session.locale", "en-GB")));

        assertThat(outcome.passed()).isTrue();
    }

    @Test
    @DisplayName("two different routes to the same state both pass")
    void anyRouteThatArrivesIsCorrect() {
        // The scenario names what must be true at the end, never the calls that produced
        // it, so a service that reaches the same place by a better path still passes.
        var expected = EndState.matching(Map.of("refund.status", "issued",
            "refund.amount", "4200"));

        var direct = leaving(Map.of("refund.status", "issued", "refund.amount", "4200",
            "tools.called", "issue_refund"));
        var roundabout = leaving(Map.of("refund.status", "issued", "refund.amount", "4200",
            "tools.called", "open_case,approve_case,issue_refund"));

        assertThat(expected.score(direct).passed()).isTrue();
        assertThat(expected.score(roundabout).passed()).isTrue();
    }

    @Test
    @DisplayName("a wrong value fails and names what was expected and what was found")
    void namesTheDifference() {
        var outcome = EndState.matching(Map.of("refund.status", "issued"))
            .score(leaving(Map.of("refund.status", "pending")));

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.describe())
            .contains("refund.status issued")
            .contains("refund.status pending");
    }

    @Test
    @DisplayName("a key the target never reported is named as missing, not as equal")
    void missingKeyIsAFailure() {
        // The agent said it issued the refund and issued nothing. This is the case the
        // whole scorer exists for.
        var outcome = EndState.matching("refund.status", "issued")
            .score(leaving(Map.of("session.locale", "en-GB")));

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.describe()).contains("refund.status nothing");
    }

    @Test
    @DisplayName("a target that reports no state gives no verdict, not a failure")
    void noStateIsUnscoreable() {
        var outcome = EndState.matching("refund.status", "issued").score(leaving(Map.of()));

        assertThat(outcome).isInstanceOf(RunOutcome.Inconclusive.class);
        assertThat(outcome.isEvidence()).isFalse();
        assertThat(outcome.describe()).contains("does this target expose what a run changed");
    }

    @Test
    @DisplayName("a scorer expecting nothing is refused, because it would pass anything")
    void emptyExpectationIsRefused() {
        assertThatThrownBy(() -> EndState.matching(Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no expected state");
    }

    @Test
    @DisplayName("the outcome is a comparison, so it carries no score")
    void comparisonCarriesNoScore() {
        var outcome = EndState.matching("refund.status", "issued")
            .score(leaving(Map.of("refund.status", "issued")));

        assertThat(outcome).isInstanceOf(RunOutcome.Asserted.class);
        assertThat(outcome.needsReview()).isFalse();
    }
}
