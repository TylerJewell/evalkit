package io.akka.evalkit.evaluation;

import akka.javasdk.evaluation.Evaluation;
import akka.javasdk.evaluation.EvaluationContext;
import akka.javasdk.evaluation.Evaluator;
import akka.javasdk.ledger.InteractionRecord;
import akka.javasdk.ledger.LedgerClient;
import io.akka.evalkit.domain.InconclusiveScore;
import io.akka.evalkit.domain.Observation;
import io.akka.evalkit.domain.RunOutcome;
import io.akka.evalkit.domain.Scorer;
import io.akka.evalkit.domain.Transcript;

import java.util.Objects;

/**
 * Runs an evalkit {@link Scorer} against a recorded interaction.
 *
 * <p>An {@link EvaluationContext} carries an interaction id and no content, so the record is
 * fetched through {@link LedgerClient} and handed to the scorer as a {@link Observation}.
 *
 * <p><b>What each outcome becomes.</b> {@code Scored}, {@code Asserted} and {@code Measured}
 * complete the effect with one {@link Evaluation}. {@code Inconclusive} calls
 * {@code inconclusive}, which is the ledger's word for the same fact. {@code Failed}
 * rethrows, because {@code Effect.Builder} offers no failed call and a defect in a scorer is
 * not a finding about the system. {@code NotReached} cannot arrive here: an interaction that
 * never happened was never recorded, so no evaluation is triggered for it.
 *
 * <p>The switch carries no {@code default}, so a further {@link RunOutcome} variant is a
 * compile error at this site.
 */
public abstract class ScorerEvaluator extends Evaluator {

    private final LedgerClient ledger;

    protected ScorerEvaluator(LedgerClient ledger) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    /** The scorer this evaluator runs. */
    protected abstract Scorer scorer();

    /**
     * What the scenario expected of this interaction.
     *
     * <p>An interaction record carries what happened and never what was wanted. A metric that
     * reads no expected outcome returns the empty string, and a judge needs a scenario to
     * supply one.
     */
    protected abstract String expectedOutcome(InteractionRecord record);

    /** The scenario name a verdict is filed under, which defaults to the interaction id. */
    protected String scenarioName(InteractionRecord record) {
        return record.interactionId();
    }

    /**
     * Scores one recorded interaction.
     *
     * <p>What {@link #evaluate} does before it writes an effect, and what a caller holding a
     * record scores it with directly.
     */
    public RunOutcome score(InteractionRecord record) {
        return scorer().score(recordingOf(record));
    }

    @Override
    public Effect evaluate(EvaluationContext context) {
        InteractionRecord record = ledger.getInteraction(context.subject().interactionId());

        RunOutcome outcome;
        try {
            outcome = score(record);
        } catch (InconclusiveScore declined) {
            return effects().inconclusive(declined.getMessage());
        }

        return switch (outcome) {
            case RunOutcome.Scored scored -> effects().complete(Evaluations.of(scored));
            case RunOutcome.Asserted asserted -> effects().complete(Evaluations.of(asserted));
            case RunOutcome.Measured measured -> effects().complete(Evaluations.of(measured));
            case RunOutcome.Inconclusive inconclusive ->
                effects().inconclusive(inconclusive.reason());
            case RunOutcome.Failed failed ->
                throw new IllegalStateException(
                    "the scorer failed on interaction " + record.interactionId()
                        + ": " + failed.reason());
            case RunOutcome.NotReached notReached ->
                throw new IllegalStateException(
                    "a recorded interaction produced " + notReached.describe()
                        + ", which names a precursor that never landed");
        };
    }

    /** The record as a scorer reads it, with the transcript a rubric interpolates. */
    public Observation recordingOf(InteractionRecord record) {
        var transcript = new Transcript(
            scenarioName(record), "", record.transcript(), record.finalResponseText(),
            expectedOutcome(record));
        return new Observation(transcript, record, java.util.Optional.empty());
    }
}
