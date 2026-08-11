package io.akka.evalkit.domain;

import akka.javasdk.ledger.Failure;
import akka.javasdk.ledger.InteractionRecord;
import akka.javasdk.ledger.ModelResponse;
import akka.javasdk.ledger.ToolCall;
import io.akka.evalkit.ledger.Interactions;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One run, as everything that scores it reads it.
 *
 * <p>{@link Transcript} holds exactly the four fields a rubric interpolates, so a judge's
 * input stays byte-identical whatever else the run recorded. The {@link InteractionRecord}
 * holds what the run did, and a metric reads that.
 *
 * <p>The record is the Akka ledger's. A run this kit executed and a run read back through
 * {@code LedgerClient} carry the same evidence and score on the same metrics.
 *
 * @param transcript  the four fields a rubric reads
 * @param interaction what the run did, recorded by the runtime or assembled by
 *                    {@link Interactions} for a target that writes no ledger
 * @param node        the specification node the answer came from, when the target tracks one.
 *                    No interaction record carries it, because it names a requirement rather
 *                    than anything the model did
 */
public record Recording(Transcript transcript, InteractionRecord interaction,
                        Optional<String> node, java.util.Map<String, String> endState) {

    /** A recording of a target that reports no state of its own. */
    public Recording(Transcript transcript, InteractionRecord interaction,
                     Optional<String> node) {
        this(transcript, interaction, node, java.util.Map.of());
    }

    public Recording {
        Objects.requireNonNull(transcript, "transcript");
        Objects.requireNonNull(interaction, "interaction");
        node = node == null ? Optional.empty() : node;
        endState = endState == null ? java.util.Map.of() : java.util.Map.copyOf(endState);
    }

    /** A recording of a target that reported its answer and nothing else. */
    public static Recording of(Transcript transcript) {
        return new Recording(transcript,
            Interactions.of("", "", transcript.systemOutput(), List.of(),
                Optional.empty(), Optional.empty()),
            Optional.empty());
    }

    public String scenarioName() {
        return transcript.scenarioName();
    }

    /** The tools invoked while answering, in the order the model called them. */
    public List<ToolCall> toolsCalled() {
        return interaction.toolCalls();
    }

    /** The names of the tools invoked, for a scorer comparing against a policy. */
    public List<String> toolNames() {
        return toolsCalled().stream().map(ToolCall::name).toList();
    }

    /** The calls the run made to a model, in order. */
    public List<ModelResponse> modelCalls() {
        return interaction.modelResponses();
    }

    /** The instruction the model was given, empty when the target reported none. */
    public String systemMessage() {
        String message = interaction.systemMessage();
        return message == null ? "" : message;
    }

    /** What ended the run without an answer, when something did. */
    public Optional<Failure> failure() {
        return interaction.failure();
    }

    /**
     * How long the graded turn took, when the target timed it.
     *
     * <p>Empty when the two instants in {@link akka.javasdk.ledger.InteractionMetadata} are
     * the same, which is what {@link Interactions#of} writes for a target reporting no timing.
     */
    public Optional<Duration> latency() {
        var metadata = interaction.metadata();
        if (metadata == null
            || metadata.callStartedAt() == null
            || metadata.callFinishedAt() == null) {
            return Optional.empty();
        }
        Duration elapsed = Duration.between(metadata.callStartedAt(), metadata.callFinishedAt());
        return elapsed.isZero() ? Optional.empty() : Optional.of(elapsed);
    }

    /**
     * The reasoning the run surfaced, in the order the calls were made.
     *
     * <p>Empty when no call reported any, which is the ordinary case for a provider with
     * reasoning switched off. A metric about planning treats that as an absence rather than
     * as an agent that did not plan &mdash; see {@link io.akka.evalkit.metric.PlanQuality}.
     */
    public String thinking() {
        return modelCalls().stream()
            .map(ModelResponse::thinking)
            .filter(reasoning -> reasoning != null && !reasoning.isEmpty())
            .reduce((first, second) -> first + "\n" + second)
            .orElse("");
    }

    /**
     * Model calls that produced text and reported no usage.
     *
     * <p>Counted rather than assumed, so a token total can be labelled a floor on evidence.
     * A target reporting no model calls at all counts none, because nothing was claimed
     * either way.
     */
    public int callsMissingUsage() {
        return (int) modelCalls().stream()
            .filter(call -> call.content() != null && !call.content().isEmpty())
            .filter(call -> !Interactions.reportedUsage(call))
            .count();
    }

    /** What the recorded calls spent, when they reported it. */
    public Tokens spend() {
        return new Tokens(interaction.totalInputTokens(), interaction.totalOutputTokens());
    }
}
