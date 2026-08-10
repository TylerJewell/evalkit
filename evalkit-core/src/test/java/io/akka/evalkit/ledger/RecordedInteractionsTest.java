package io.akka.evalkit.ledger;

import akka.javasdk.evaluation.Evaluation;
import akka.javasdk.ledger.EvaluationRecord;
import akka.javasdk.ledger.InteractionRecord;
import akka.javasdk.ledger.LedgerClient;
import io.akka.evalkit.domain.Precursor;
import io.akka.evalkit.domain.RunOutcome;
import io.akka.evalkit.domain.SystemUnderTest;
import io.akka.evalkit.evaluation.Evaluations;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scoring runs evalkit did not execute.
 *
 * <p>A campaign over this target reads what the platform recorded, so a corpus scored in a
 * build and traffic scored in production reach the same report through the same metrics.
 */
@DisplayName("RecordedInteractions · a campaign over interactions the platform recorded")
class RecordedInteractionsTest {

    private static InteractionRecord record(String id, String answer) {
        var built = Interactions.of("session-" + id, "be helpful", "when do I get my refund?",
            List.of(Interactions.calling(Interactions.response(answer),
                Interactions.tool("search_kb"))),
            Optional.empty(), Optional.empty());
        return new InteractionRecord(id, built.sessionId(), "refund-agent", built.flowId(),
            built.metadata(), built.systemMessage(), built.inputMessage(),
            built.modelResponses(), built.toolCallResponses(), built.taskContext(),
            built.failure(), built.timestamp());
    }

    private static LedgerClient ledgerOf(Map<String, InteractionRecord> held) {
        return new LedgerClient() {
            @Override
            public InteractionRecord getInteraction(String interactionId) {
                var record = held.get(interactionId);
                if (record == null) {
                    throw new java.util.NoSuchElementException(interactionId);
                }
                return record;
            }

            @Override
            public CompletionStage<InteractionRecord> getInteractionAsync(String id) {
                return java.util.concurrent.CompletableFuture.completedFuture(getInteraction(id));
            }

            @Override
            public EvaluationRecord getEvaluation(String evaluationId) {
                throw new UnsupportedOperationException("no evaluation is read here");
            }

            @Override
            public CompletionStage<EvaluationRecord> getEvaluationAsync(String id) {
                throw new UnsupportedOperationException("no evaluation is read here");
            }
        };
    }

    @Test
    @DisplayName("a recorded interaction is read back as a reply")
    void aRecordedInteractionIsReadBack() {
        var held = Map.of("interaction-1", record("interaction-1", "the refund takes 30 days"));
        var target = new RecordedInteractions(ledgerOf(held),
            Map.of("interaction-1", "a refund question answered in production"));

        var prepared = target.prepare(Precursor.Fixture.named("interaction-1"));

        assertThat(prepared).isInstanceOf(SystemUnderTest.Prepared.Ready.class);
        var ready = (SystemUnderTest.Prepared.Ready) prepared;
        var reply = target.submit(ready.sessionId(), "when do I get my refund?");

        assertThat(reply.text()).isEqualTo("the refund takes 30 days");
        assertThat(reply.toolsCalled()).hasSize(1);
    }

    /**
     * The case this target is known to catch.
     *
     * <p>A ledger that answered every id would let a campaign report on interactions that do
     * not exist, which is the empty search space this repository has recorded twice.
     */
    @Test
    @DisplayName("an interaction the ledger does not hold never reaches the question")
    void anAbsentInteractionIsNotReached() {
        var target = new RecordedInteractions(ledgerOf(Map.of()),
            Map.of("interaction-9", "an interaction nobody recorded"));

        var prepared = target.prepare(Precursor.Fixture.named("interaction-9"));

        assertThat(prepared).isInstanceOf(SystemUnderTest.Prepared.Failed.class);
        assertThat(((SystemUnderTest.Prepared.Failed) prepared).reason())
            .contains("holds no interaction");
    }

    /** This target says nothing to anything, so a precursor that walks a conversation fails. */
    @Test
    @DisplayName("a replay precursor cannot be walked against a recorded interaction")
    void aReplayPrecursorCannotBeWalked() {
        var target = new RecordedInteractions(ledgerOf(Map.of()), Map.of());

        var prepared = target.prepare(Precursor.replay("I want a refund"));

        assertThat(prepared).isInstanceOf(SystemUnderTest.Prepared.Failed.class);
        assertThat(((SystemUnderTest.Prepared.Failed) prepared).reason())
            .contains("replays nothing");
    }

    /**
     * {@code Evaluation.score} is an unbounded double and {@code Verdict} pairs a 1-to-10
     * score with its band, so a score outside that range is absent evidence.
     */
    @Test
    @DisplayName("an evaluation scoring outside 1 to 10 reads back as unscoreable")
    void anOutOfRangeScoreIsUnscoreable() {
        var evaluation = Evaluation.of(true, "scored elsewhere")
            .withScore(42.0)
            .withAttribute(Evaluations.KIND, "scored");

        var outcome = Evaluations.read(evaluation);

        assertThat(outcome).isInstanceOf(RunOutcome.Unscoreable.class);
        assertThat(outcome.describe()).contains("outside the 1 to 10");
    }

    @Test
    @DisplayName("an evaluation inside the band range reads back as a verdict")
    void anInRangeScoreIsAVerdict() {
        var evaluation = Evaluation.of(true, "the agent stated the window")
            .withScore(9.0)
            .withAttribute(Evaluations.KIND, "scored")
            .withAttribute(Evaluations.SCENARIO, "refund-window")
            .withAttribute(Evaluations.RUBRIC_ID, "scenario-judge")
            .withAttribute(Evaluations.RUBRIC_VERSION, "3");

        var outcome = Evaluations.read(evaluation);

        assertThat(outcome).isInstanceOf(RunOutcome.Scored.class);
        var verdict = ((RunOutcome.Scored) outcome).verdict();
        assertThat(verdict.score()).isEqualTo(9);
        assertThat(verdict.rubricId()).isEqualTo("scenario-judge");
        assertThat(verdict.scenarioName()).isEqualTo("refund-window");
    }

    /** A comparison carries no score, so reading one back as a judgement would invent it. */
    @Test
    @DisplayName("an asserted evaluation is not read back as a model judgement")
    void anAssertedEvaluationIsNotAVerdict() {
        var evaluation = Evaluations.of(new RunOutcome.Asserted(true, "REFUND-004", "REFUND-004"));

        assertThat(Evaluations.read(evaluation)).isInstanceOf(RunOutcome.Unscoreable.class);
    }
}
