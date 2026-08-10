package io.akka.evalkit.ledger;

import akka.javasdk.ledger.InteractionRecord;
import akka.javasdk.ledger.LedgerClient;
import io.akka.evalkit.domain.Precursor;
import io.akka.evalkit.domain.SystemUnderTest;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A target that reads interactions the platform already recorded and causes none.
 *
 * <p>{@link SystemUnderTest} runs arrange and act against a live service. This one runs
 * neither: {@link #prepare} accepts nothing but {@link Precursor.Fixture} naming a recorded
 * interaction, and {@link #submit} returns what that interaction already said.
 *
 * <p>A campaign over this target reports on runs evalkit did not execute, so a corpus scored
 * in CI and traffic scored in production reach the same report through the same metrics.
 *
 * <p><b>Nothing here proves reachability.</b> Every run is a recorded starting point, so
 * {@code walked} counts none of them. A recorded interaction shows what a user did reach,
 * which is a stronger claim than a fixture and a different one from a replay.
 */
public final class RecordedInteractions implements SystemUnderTest {

    /** The fixture parameter naming the interaction to read. */
    public static final String INTERACTION_ID = "interactionId";

    private final LedgerClient ledger;
    private final Map<String, String> fixtures;

    /**
     * @param fixtures the recorded interactions this target can read, each with a one-line
     *                 description a run summary prints for a reader who does not know them
     */
    public RecordedInteractions(LedgerClient ledger, Map<String, String> fixtures) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.fixtures = Map.copyOf(fixtures);
    }

    @Override
    public Map<String, String> fixtures() {
        return fixtures;
    }

    /**
     * Reads the interaction a scenario names.
     *
     * <p>A precursor that walks a conversation cannot be honoured, because this target says
     * nothing to anything. That is {@link Prepared.Failed}, which becomes {@code NotReached}
     * and stays out of the pass rate.
     */
    @Override
    public Prepared prepare(Precursor precursor) {
        if (!(precursor instanceof Precursor.Fixture fixture)) {
            return new Prepared.Failed(
                "this target reads recorded interactions and replays nothing, so "
                    + precursor.describe() + " cannot be walked");
        }
        String interactionId = fixture.parameters().getOrDefault(INTERACTION_ID, fixture.name());
        InteractionRecord record;
        try {
            record = ledger.getInteraction(interactionId);
        } catch (RuntimeException notFound) {
            return new Prepared.Failed(
                "the ledger holds no interaction " + interactionId + ": " + notFound.getMessage());
        }
        if (record == null) {
            return new Prepared.Failed("the ledger holds no interaction " + interactionId);
        }
        return new Prepared.Ready(interactionId, record.transcript());
    }

    /**
     * What the recorded interaction said, whatever the graded turn asks.
     *
     * <p>The turn is not sent anywhere. A scenario over this target states the outcome it
     * expects of an answer that already exists.
     */
    @Override
    public Reply submit(String sessionId, String userText) {
        InteractionRecord record = ledger.getInteraction(sessionId);
        return new Reply(record.finalResponseText(), Optional.empty(), latency(record),
            record.toolCalls(), record.modelResponses(), record.systemMessage(),
            record.failure());
    }

    private static Optional<java.time.Duration> latency(InteractionRecord record) {
        var metadata = record.metadata();
        if (metadata == null
            || metadata.callStartedAt() == null
            || metadata.callFinishedAt() == null) {
            return Optional.empty();
        }
        var elapsed = java.time.Duration.between(
            metadata.callStartedAt(), metadata.callFinishedAt());
        return elapsed.isZero() ? Optional.empty() : Optional.of(elapsed);
    }

}
