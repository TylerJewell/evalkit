package io.akka.evalkit.metric;

import io.akka.evalkit.domain.NoVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading a judge that was asked about several subjects at once.
 *
 * <p>A metric aggregates the share of subjects that went its way, so a block the parser drops
 * moves the score without anybody seeing it move.
 */
@DisplayName("JudgementReply · a judge asked about several subjects")
class JudgementReplyTest {

    @Test
    @DisplayName("every block becomes one judgement")
    void everyBlockBecomesAJudgement() {
        String reply = """
            SUBJECT: the claim that the window is 30 days
            VERDICT: yes
            REASON: passage 2 states a 30-day window

            SUBJECT: the claim that shipping is refunded
            VERDICT: no
            REASON: no passage mentions shipping
            """;

        var judgements = JudgementReply.read(reply);

        assertThat(judgements).hasSize(2);
        assertThat(judgements.get(0).affirmed()).isTrue();
        assertThat(judgements.get(0).subject()).isEqualTo("the claim that the window is 30 days");
        assertThat(judgements.get(1).affirmed()).isFalse();
        assertThat(judgements.get(1).reason()).isEqualTo("no passage mentions shipping");
    }

    @Test
    @DisplayName("a reply wrapped in markdown is still read")
    void markdownAroundTheLabelsIsStripped() {
        String reply = """
            **SUBJECT:** the first claim
            **VERDICT:** yes
            **REASON:** it holds
            """;

        var judgements = JudgementReply.read(reply);

        assertThat(judgements).hasSize(1);
        assertThat(judgements.get(0).subject()).isEqualTo("the first claim");
        assertThat(judgements.get(0).affirmed()).isTrue();
    }

    @Test
    @DisplayName("a block with no reason keeps its verdict")
    void aBlockWithNoReasonKeepsItsVerdict() {
        var judgements = JudgementReply.read("SUBJECT: a claim\nVERDICT: yes");

        assertThat(judgements).hasSize(1);
        assertThat(judgements.get(0).affirmed()).isTrue();
        assertThat(judgements.get(0).reason()).isEmpty();
    }

    /**
     * The case the parser is known to catch.
     *
     * <p>A hedge read as agreement would raise every metric by the share of subjects the
     * model would not commit on.
     */
    @Test
    @DisplayName("a hedged verdict counts against the metric")
    void aHedgedVerdictIsNotAffirmed() {
        var judgements = JudgementReply.read("SUBJECT: a claim\nVERDICT: partially");

        assertThat(judgements.get(0).affirmed()).isFalse();
    }

    /**
     * An unreadable reply and a run with nothing to judge are different facts.
     *
     * <p>Returning an empty list for both would score a metric 1 on the strength of a reply
     * nobody could parse.
     */
    @Test
    @DisplayName("a reply naming no subject reaches no verdict")
    void anUnreadableReplyReachesNoVerdict() {
        assertThatThrownBy(() -> JudgementReply.read("I could not assess this."))
            .isInstanceOf(NoVerdict.class)
            .hasMessageContaining("named no subject");

        assertThatThrownBy(() -> JudgementReply.read(""))
            .isInstanceOf(NoVerdict.class);
    }
}
