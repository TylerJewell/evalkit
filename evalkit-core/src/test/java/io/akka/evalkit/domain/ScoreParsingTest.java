package io.akka.evalkit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The only place arbitrary model text becomes a number.
 *
 * <p>A judge answers with whatever it likes. Everything downstream trusts the integer this
 * produces: the band, the report column, the pass total. A wrong number here is invisible,
 * because a fabricated 3 and an honest 3 look identical in every artefact.
 */
@DisplayName("Grade.parseScore · reading a judge's reply")
class ScoreParsingTest {

    @ParameterizedTest(name = "reads {0}")
    @ValueSource(strings = {"8", " 8 ", "8.", "Score: 8", "8/10", "**8**", "score=8"})
    @DisplayName("the shapes a compliant judge returns all read as the score")
    void compliantReplies(String reply) {
        assertThat(Grade.parseScore(reply)).contains(8);
    }

    @Test
    @DisplayName("ten reads as ten rather than as one")
    void tenIsNotOne() {
        // The alternation puts 10 before the single digits for this reason.
        assertThat(Grade.parseScore("10")).contains(10);
        assertThat(Grade.parseScore("10/10")).contains(10);
    }

    @ParameterizedTest(name = "returns empty for {0}")
    @ValueSource(strings = {"", "   ", "I cannot assess this conversation.",
        "The answer is unclear.", "0", "11", "100", "3.5", "N/A"})
    @DisplayName("a reply with no score in range returns empty rather than a default")
    void unreadableReplies(String reply) {
        // A default would be a fabricated finding, and a fabricated 1 and a fabricated
        // 10 are both worse than an admission that the judge did not answer.
        assertThat(Grade.parseScore(reply)).isEmpty();
    }

    @Test
    @DisplayName("null returns empty rather than throwing")
    void nullReply() {
        assertThat(Grade.parseScore(null)).isEmpty();
    }

    @Test
    @DisplayName("a chatty reply returns the first number in range, which may not be the score")
    void aChattyReplyIsMisread() {
        // Rubric v2 asks for "a single value from 1 to 10 and nothing else", and a judge
        // that complies is read correctly. A judge that explains itself is not: the first
        // integer in range wins, whatever it was counting.
        assertThat(Grade.parseScore("1 of 5 criteria were met, so I score this 8"))
            .contains(1);
        assertThat(Grade.parseScore("Section 3 of the policy applies. Score: 9"))
            .contains(3);

        // The failure is silent. Both readings produce a valid band and a plausible row.
        assertThat(Band.of(1)).isNotEqualTo(Band.of(8));
    }

    @Test
    @DisplayName("a year or an amount in the reply is not mistaken for a score")
    void multiDigitNumbersAreNotSplit() {
        // The word boundaries stop 2024 reading as 2 and 240 reading as 4.
        assertThat(Grade.parseScore("the policy changed in 2024")).isEmpty();
        assertThat(Grade.parseScore("a refund of 240 euros")).isEmpty();
        assertThat(Grade.parseScore("30 days")).isEmpty();
    }

    @Test
    @DisplayName("any input returns nothing or a value inside the band range, and never throws")
    void anyInputIsSafe() {
        var random = new Random(20260808L);
        var alphabet = "0123456789 \n\t./*-:abcXYZé[]{}".toCharArray();

        for (int i = 0; i < 20_000; i++) {
            var text = new StringBuilder();
            for (int c = random.nextInt(24); c > 0; c--) {
                text.append(alphabet[random.nextInt(alphabet.length)]);
            }

            var parsed = Grade.parseScore(text.toString());

            // Every score reaching Grade must be one Band.of can hold. A value outside
            // 1 to 10 would throw inside the constructor and lose the run.
            parsed.ifPresent(score -> {
                assertThat(score).isBetween(1, 10);
                assertThat(Band.of(score)).isNotNull();
            });
        }
    }
}
