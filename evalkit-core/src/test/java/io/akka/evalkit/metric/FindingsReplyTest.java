package io.akka.evalkit.metric;

import io.akka.evalkit.domain.InconclusiveScore;
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
@DisplayName("FindingsReply · a judge asked about several subjects")
class FindingsReplyTest {

    @Test
    @DisplayName("every block becomes one finding")
    void everyBlockBecomesAJudgement() {
        String reply = """
            SUBJECT: the claim that the window is 30 days
            VERDICT: yes
            REASON: passage 2 states a 30-day window

            SUBJECT: the claim that shipping is refunded
            VERDICT: no
            REASON: no passage mentions shipping
            """;

        var findings = FindingsReply.read(reply);

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).affirmed()).isTrue();
        assertThat(findings.get(0).claim()).isEqualTo("the claim that the window is 30 days");
        assertThat(findings.get(1).affirmed()).isFalse();
        assertThat(findings.get(1).explanation()).isEqualTo("no passage mentions shipping");
    }

    @Test
    @DisplayName("a reply wrapped in markdown is still read")
    void markdownAroundTheLabelsIsStripped() {
        String reply = """
            **SUBJECT:** the first claim
            **VERDICT:** yes
            **REASON:** it holds
            """;

        var findings = FindingsReply.read(reply);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).claim()).isEqualTo("the first claim");
        assertThat(findings.get(0).affirmed()).isTrue();
    }

    @Test
    @DisplayName("a block with no reason keeps its verdict")
    void aBlockWithNoReasonKeepsItsVerdict() {
        var findings = FindingsReply.read("SUBJECT: a claim\nVERDICT: yes");

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).affirmed()).isTrue();
        assertThat(findings.get(0).explanation()).isEmpty();
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
        var findings = FindingsReply.read("SUBJECT: a claim\nVERDICT: partially");

        assertThat(findings.get(0).affirmed()).isFalse();
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
        assertThatThrownBy(() -> FindingsReply.read("I could not assess this."))
            .isInstanceOf(InconclusiveScore.class)
            .hasMessageContaining("named no subject");

        assertThatThrownBy(() -> FindingsReply.read(""))
            .isInstanceOf(InconclusiveScore.class);
    }
}
