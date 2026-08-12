package io.akka.evalkit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A judge that states why, and a judge that does not, read by what their rubric asked for.
 */
@DisplayName("Explained grades · a score with the judge's own sentence beside it")
class ReasonedGradeTest {

    private static final Rubric BARE = Rubric.load("scenario-judge", 2);
    private static final Rubric REASONED = Rubric.load("scenario-judge", 3);
    private static final Rubric EXPLAINED = Rubric.load("scenario-judge", 4);

    private static Transcript transcript() {
        return new Transcript("refund-outside-window", "",
            "user: 45 days later\nagent: the window is 30 days", "", "Refuses");
    }

    @Test
    @DisplayName("v2 asks for a number, and v3 and v4 ask for a number and a sentence")
    void rubricsDeclareWhatTheyAskedFor() {
        assertThat(BARE.statesExplanation()).isFalse();
        assertThat(REASONED.statesExplanation()).isTrue();
        assertThat(EXPLAINED.statesExplanation()).isTrue();
    }

    @Test
    @DisplayName("v3 keeps v2's bands word for word, so the two are comparable")
    void bandsAreUnchangedAcrossVersions() {
        var bands = bandBlock(BARE);

        // Asserted before the comparison. An empty block is contained in anything, so a
        // selector that matched nothing would report the bands as identical while reading
        // neither. Three sentences, and the range each one names.
        assertThat(bands).contains("1-3.").contains("4-7.").contains("8- 10.");
        assertThat(flatten(REASONED))
            .as("scenario-judge v3 must score on v2's bands or the two cannot be compared")
            .contains(bands);
        assertThat(flatten(EXPLAINED))
            .as("scenario-judge v4 must score on v2's bands or the two cannot be compared")
            .contains(bands);
    }

    @Test
    @DisplayName("v4 differs from v3 in the label it asks under and nothing else")
    void v4AsksTheSameQuestionUnderADifferentLabel() {
        // The whole claim for v4 is that only the output label moved. Anything else that
        // differs is a change to what the judge was asked, which would make the two scales
        // incomparable while looking like a rename.
        assertThat(EXPLAINED.promptTemplate().replace("EXPLANATION:", "REASON:"))
            .isEqualTo(REASONED.promptTemplate());
    }

    @Test
    @DisplayName("a v4 reply is read under its own label")
    void v4RepliesAreRead() {
        var grade = Grade.read("refund", EXPLAINED,
            "SCORE: 9\nEXPLANATION: The agent refused and cited the 30-day window.")
            .orElseThrow();

        assertThat(grade.score()).isEqualTo(9);
        assertThat(grade.band()).isEqualTo(Band.FAITHFUL);
        assertThat(grade.rubricVersion()).isEqualTo(4);
        assertThat(grade.explanation())
            .isEqualTo("The agent refused and cited the 30-day window.");
    }

    @Test
    @DisplayName("v3's label still reads, because scores recorded under it are still read back")
    void v3RepliesStillRead() {
        var grade = Grade.read("refund", REASONED,
            "SCORE: 9\nREASON: The agent refused and cited the 30-day window.")
            .orElseThrow();

        assertThat(grade.rubricVersion()).isEqualTo(3);
        assertThat(grade.explanation())
            .isEqualTo("The agent refused and cited the 30-day window.");
    }

    @Test
    @DisplayName("a v4 reply that lost its label is unreadable, not read as a bare number")
    void v4WithoutItsLabelIsUnreadable() {
        assertThat(Grade.read("refund", EXPLAINED, "8")).isEmpty();
        assertThat(Grade.read("refund", EXPLAINED, "I scored this 8 out of 10")).isEmpty();
    }

    @Test
    @DisplayName("the band comparison catches a rubric that reworded a band")
    void bandComparisonCatchesARewordedBand() {
        var reworded = new Rubric("stub", 1, stubTemplate(
            "If <simulation_history> does not match <expected_outcome>, return a 1."));

        assertThat(flatten(reworded)).doesNotContain(bandBlock(BARE));
    }

    @Test
    @DisplayName("a labelled reply yields the score and the sentence")
    void readsScoreAndReason() {
        var grade = Grade.read("refund", REASONED,
            "SCORE: 9\nREASON: The agent refused and named the 30-day window.");

        assertThat(grade).isPresent();
        assertThat(grade.orElseThrow().score()).isEqualTo(9);
        assertThat(grade.orElseThrow().band()).isEqualTo(Band.FAITHFUL);
        assertThat(grade.orElseThrow().explanation())
            .isEqualTo("The agent refused and named the 30-day window.");
    }

    @Test
    @DisplayName("a reason spanning several lines arrives whole")
    void readsAMultiLineReason() {
        var grade = Grade.read("refund", REASONED,
            "SCORE: 4\nREASON: The agent named the window\nbut offered a refund anyway.");

        assertThat(grade.orElseThrow().explanation())
            .isEqualTo("The agent named the window\nbut offered a refund anyway.");
    }

    @Test
    @DisplayName("a model that wrapped its labels in markdown is still read")
    void readsThroughMarkdown() {
        var grade = Grade.read("refund", REASONED,
            "**SCORE:** 7\n**REASON:** Partly right.");

        assertThat(grade.orElseThrow().score()).isEqualTo(7);
        assertThat(grade.orElseThrow().explanation()).isEqualTo("Partly right.");
    }

    @Test
    @DisplayName("a reason written before the score does not swallow it")
    void readsLabelsInEitherOrder() {
        var grade = Grade.read("refund", REASONED,
            "REASON: The agent refused.\nSCORE: 8");

        assertThat(grade.orElseThrow().score()).isEqualTo(8);
        assertThat(grade.orElseThrow().explanation()).isEqualTo("The agent refused.");
    }

    @Test
    @DisplayName("a score that arrived without its reason is still a score")
    void keepsAScoreThatLostItsReason() {
        var grade = Grade.read("refund", REASONED, "SCORE: 3");

        assertThat(grade.orElseThrow().score()).isEqualTo(3);
        assertThat(grade.orElseThrow().statesExplanation()).isFalse();
    }

    @Test
    @DisplayName("a reasoned rubric does not fall back to reading a bare number")
    void refusesToGuessUnderAReasonedRubric() {
        // "8" alone is a valid reply to v2 and an incomplete reply to v3. Reading it here
        // would take the first integer out of any sentence the model wrote instead.
        assertThat(Grade.read("refund", REASONED, "8")).isEmpty();
        assertThat(Grade.read("refund", REASONED, "I scored this 8 out of 10")).isEmpty();

        assertThat(Grade.read("refund", BARE, "8")).isPresent();
    }

    @Test
    @DisplayName("a bare rubric produces a grade with nothing to state")
    void bareRubricStatesNoReason() {
        var grade = Grade.read("refund", BARE, "Score: 9").orElseThrow();

        assertThat(grade.score()).isEqualTo(9);
        assertThat(grade.statesExplanation()).isFalse();
        assertThat(grade.rubricVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("an unreadable reply is empty under either rubric")
    void unreadableRepliesAreEmpty() {
        assertThat(Grade.read("refund", REASONED, "I cannot assess this")).isEmpty();
        assertThat(Grade.read("refund", REASONED, null)).isEmpty();
        assertThat(Grade.read("refund", BARE, "I cannot assess this")).isEmpty();
    }

    @Test
    @DisplayName("a score outside the band range is not a grade")
    void refusesAScoreOutsideTheRange() {
        assertThat(Grade.read("refund", REASONED, "SCORE: 47\nREASON: nonsense")).isEmpty();
    }

    @Test
    @DisplayName("the row prints the judge's sentence, and prints none when there is none")
    void describesWithAndWithoutAReason() {
        var reasoned = new RunOutcome.Scored(
            Grade.of("refund", REASONED, 2, "The agent gave the refund."));
        var bare = new RunOutcome.Scored(Grade.of("refund", BARE, 2));

        assertThat(reasoned.describe()).isEqualTo("NO_MATCH (2/10) — The agent gave the refund.");
        assertThat(bare.describe()).isEqualTo("NO_MATCH (2/10)");
    }

    @Test
    @DisplayName("a measurement carries a reason only when one was produced")
    void measuredCarriesAReasonWhenThereIsOne() {
        var counted = new RunOutcome.Measured("tool-permission", 1, 0.5, 1.0, false);
        var aligned = new RunOutcome.Measured("task-completion", 1, 0.4, 0.5, false,
            "The agent never booked the table.");

        assertThat(counted.statesExplanation()).isFalse();
        assertThat(counted.describe()).isEqualTo("tool-permission v1: 0.50 against 1.00");
        assertThat(aligned.describe())
            .isEqualTo("task-completion v1: 0.40 against 0.50 — The agent never booked the table.");
    }

    @Test
    @DisplayName("a transcript still renders into the reasoned rubric")
    void reasonedRubricInterpolates() {
        assertThat(REASONED.render(transcript()))
            .contains("the window is 30 days")
            .doesNotContain("{simulation_history}");
    }

    /**
     * The sentences that define what a score means, as one line.
     *
     * <p>Both rubrics wrap the band sentences mid-line, so the comparison runs on flattened
     * text. v2 closes on its bands, which is what makes the tail of the file the block.
     */
    private static String bandBlock(Rubric rubric) {
        var flat = flatten(rubric);
        return flat.substring(flat.indexOf("If <simulation_history> does not match"));
    }

    private static String flatten(Rubric rubric) {
        return rubric.promptTemplate().replaceAll("\\s+", " ").strip();
    }

    /** A template carrying the placeholders a rubric requires, plus the given line. */
    private static String stubTemplate(String line) {
        return "{replay_history} {simulation_history} {system_output} {expected_outcome}\n" + line;
    }
}
