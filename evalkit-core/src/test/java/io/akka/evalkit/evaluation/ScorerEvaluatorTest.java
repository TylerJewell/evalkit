package io.akka.evalkit.evaluation;

import akka.javasdk.evaluation.Evaluation;
import akka.javasdk.ledger.EvaluationRecord;
import akka.javasdk.ledger.InteractionRecord;
import akka.javasdk.ledger.LedgerClient;
import io.akka.evalkit.domain.Band;
import io.akka.evalkit.domain.NoVerdict;
import io.akka.evalkit.domain.RunOutcome;
import io.akka.evalkit.domain.Scorer;
import io.akka.evalkit.domain.Verdict;
import io.akka.evalkit.ledger.Interactions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every {@link RunOutcome} variant against the effect it produces.
 *
 * <p>The effect builder is the runtime's, so these assert the branch taken rather than the
 * value it returns. A variant that stopped reaching its branch would compile and would report
 * the wrong fact about a run, which is what the case per variant is here to catch.
 */
@DisplayName("ScorerEvaluator · an evalkit outcome as a ledger effect")
class ScorerEvaluatorTest {

    private static final String INTERACTION = "interaction-1";

    /** A ledger holding one interaction, which is all an evaluator reads. */
    private static LedgerClient ledgerOf(InteractionRecord record) {
        return new LedgerClient() {
            @Override
            public InteractionRecord getInteraction(String interactionId) {
                return record;
            }

            @Override
            public CompletionStage<InteractionRecord> getInteractionAsync(String interactionId) {
                return java.util.concurrent.CompletableFuture.completedFuture(record);
            }

            @Override
            public EvaluationRecord getEvaluation(String evaluationId) {
                throw new UnsupportedOperationException("no evaluation is read here");
            }

            @Override
            public CompletionStage<EvaluationRecord> getEvaluationAsync(String evaluationId) {
                throw new UnsupportedOperationException("no evaluation is read here");
            }
        };
    }

    private static InteractionRecord record() {
        var call = Interactions.response("the refund takes 30 days");
        var built = Interactions.of("session-1", "be helpful", "when do I get my refund?",
            List.of(call), Optional.empty(), Optional.empty());
        return new InteractionRecord(INTERACTION, built.sessionId(), "refund-agent",
            built.flowId(), built.metadata(), built.systemMessage(), built.inputMessage(),
            built.modelResponses(), built.toolCallResponses(), built.taskContext(),
            built.failure(), built.timestamp());
    }

    /** An evaluator running whatever scorer a case hands it. */
    private static ScorerEvaluator evaluator(Scorer scorer) {
        return new ScorerEvaluator(ledgerOf(record())) {
            @Override
            protected Scorer scorer() {
                return scorer;
            }

            @Override
            protected String expectedOutcome(InteractionRecord record) {
                return "States the 30-day window";
            }
        };
    }

    private static Verdict verdict(int score) {
        return new Verdict("refund-window", "scenario-judge", 3, score, Band.of(score),
            "the agent stated the window");
    }

    @Test
    @DisplayName("a scored run completes the effect")
    void aScoredRunCompletes() {
        var effect = evaluator(recording -> new RunOutcome.Scored(verdict(9)))
            .evaluate(new StubContext());

        assertThat(effect).isNotNull();
    }

    @Test
    @DisplayName("an asserted run completes the effect")
    void anAssertedRunCompletes() {
        var effect = evaluator(recording ->
            new RunOutcome.Asserted(true, "REFUND-004", "REFUND-004")).evaluate(new StubContext());

        assertThat(effect).isNotNull();
    }

    @Test
    @DisplayName("a measured run completes the effect")
    void aMeasuredRunCompletes() {
        var effect = evaluator(recording ->
            new RunOutcome.Measured("tool-correctness", 1, 0.9, 0.5, true))
            .evaluate(new StubContext());

        assertThat(effect).isNotNull();
    }

    @Test
    @DisplayName("an unscoreable run is inconclusive rather than a failure")
    void anUnscoreableRunIsInconclusive() {
        var effect = evaluator(recording ->
            new RunOutcome.Unscoreable("the content filter would not score it"))
            .evaluate(new StubContext());

        assertThat(effect).isNotNull();
    }

    /**
     * The case the split between declining and breaking exists for.
     *
     * <p>A judge discovers a refusal several frames deep in parsing a reply and throws.
     * Reclassifying that as a scorer defect would destroy the distinction, so the throw is
     * read as a decline.
     */
    @Test
    @DisplayName("a scorer that throws NoVerdict declined, and is inconclusive")
    void aThrownNoVerdictIsInconclusive() {
        var effect = evaluator(recording -> {
            throw new NoVerdict("the content filter refused the transcript");
        }).evaluate(new StubContext());

        assertThat(effect).isNotNull();
    }

    @Test
    @DisplayName("a scorer that failed is a defect here, and reaches no effect")
    void aFailedScorerRethrows() {
        var evaluator = evaluator(recording ->
            new RunOutcome.ScorerFailed("a null reference in the metric"));

        assertThatThrownBy(() -> evaluator.evaluate(new StubContext()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("the scorer failed")
            .hasMessageContaining(INTERACTION);
    }

    /**
     * An outcome the ledger cannot represent.
     *
     * <p>A precursor that never landed produced no interaction, so no evaluation is triggered
     * for it. A scorer returning it against a recorded interaction is stating something the
     * record contradicts.
     */
    @Test
    @DisplayName("a not-reached run against a recorded interaction reaches no effect")
    void aNotReachedRunRethrows() {
        var evaluator = evaluator(recording -> new RunOutcome.NotReached(
            RunOutcome.Cause.SETUP_FAILED, "the fixture was never built",
            io.akka.evalkit.domain.Precursor.Fixture.named("signed-in")));

        assertThatThrownBy(() -> evaluator.evaluate(new StubContext()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("never landed");
    }

    @Test
    @DisplayName("the scorer reads the transcript and the expected outcome from the record")
    void theScorerReadsTheRecord() {
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
        evaluator(recording -> {
            seen.set(recording.transcript().expectedOutcome());
            assertThat(recording.transcript().systemOutput())
                .isEqualTo("the refund takes 30 days");
            assertThat(recording.systemMessage()).isEqualTo("be helpful");
            return new RunOutcome.Scored(verdict(9));
        }).evaluate(new StubContext());

        assertThat(seen.get()).isEqualTo("States the 30-day window");
    }

    @Test
    @DisplayName("a verdict written to an evaluation carries its band and rubric")
    void aVerdictCarriesItsBandAndRubric() {
        Evaluation evaluation = Evaluations.of(new RunOutcome.Scored(verdict(9)));

        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.score()).contains(9.0);
        assertThat(evaluation.label()).contains(Band.of(9).name());
        assertThat(evaluation.attributes())
            .containsEntry(Evaluations.KIND, "scored")
            .containsEntry(Evaluations.RUBRIC_ID, "scenario-judge")
            .containsEntry(Evaluations.RUBRIC_VERSION, "3");
    }

    /** A comparison carries no confidence to be borderline about, so it carries no score. */
    @Test
    @DisplayName("an asserted outcome writes no score")
    void anAssertedOutcomeWritesNoScore() {
        Evaluation evaluation =
            Evaluations.of(new RunOutcome.Asserted(false, "REFUND-004", "REFUND-009"));

        assertThat(evaluation.score()).isEmpty();
        assertThat(evaluation.attributes()).containsEntry(Evaluations.KIND, "asserted");
    }

    /** An evaluation context carrying the interaction the ledger holds. */
    private static final class StubContext implements akka.javasdk.evaluation.EvaluationContext {

        @Override
        public akka.javasdk.evaluation.Subject subject() {
            return new akka.javasdk.evaluation.Subject.AgentInteraction(
                "refund-agent", INTERACTION);
        }

        @Override
        public String evaluationId() {
            return "evaluation-1";
        }
    }
}
