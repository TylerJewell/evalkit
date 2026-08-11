package io.akka.evalkit.ledger;

import akka.javasdk.agent.MessageContent;
import akka.javasdk.ledger.Failure;
import akka.javasdk.ledger.InteractionMetadata;
import akka.javasdk.ledger.InteractionRecord;
import akka.javasdk.ledger.ModelConfig;
import akka.javasdk.ledger.ModelResponse;
import akka.javasdk.ledger.ToolCall;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds an {@link InteractionRecord} for a target that did not write one.
 *
 * <p>An Akka agent's interaction is recorded by the runtime and read back through
 * {@code LedgerClient}. A target reached over a port reports what it can, and these factories
 * assemble the same record from it, so a scorer reads one type whatever produced the run.
 *
 * <p>A component the target did not report is empty here. {@link InteractionRecord#toolCalls()}
 * returns an empty list for a target that reports only its final answer, and every metric
 * reading tool calls handles that absence.
 */
public final class Interactions {

    /** The instant a record carries when the target reported no timing. */
    public static final Instant UNTIMED = Instant.EPOCH;

    private Interactions() {}

    /**
     * A record holding what a target reported about one graded turn.
     *
     * @param sessionId    what the graded turn was addressed to
     * @param systemMessage the instruction the model was given, empty when unreported
     * @param userText     the graded turn
     * @param responses    the model calls behind the answer, in order
     * @param latency      how long the turn took, when the target timed it
     * @param failure      what ended the run without an answer, when something did
     */
    public static InteractionRecord of(String sessionId, String systemMessage, String userText,
                                       List<ModelResponse> responses,
                                       Optional<Duration> latency,
                                       Optional<Failure> failure) {
        Instant started = UNTIMED;
        Instant finished = latency.map(started::plus).orElse(started);
        var metadata = new InteractionMetadata(
            null, Map.of(), started, finished, InteractionMetadata.FinishReason.UNSPECIFIED);
        return new InteractionRecord(
            "", sessionId, "", Optional.empty(), metadata, systemMessage,
            List.of(new MessageContent.TextMessageContent(userText)),
            responses, List.of(), Optional.empty(), failure, started);
    }

    /**
     * The same record under an interaction id.
     *
     * <p>A record this kit assembled carries no id, because nothing recorded it. A campaign
     * names its runs after the scenario that caused them, which is what a dataset entry is
     * filed and re-scored under.
     */
    public static InteractionRecord identified(InteractionRecord record, String interactionId) {
        return new InteractionRecord(interactionId, record.sessionId(),
            record.agentComponentId(), record.flowId(), record.metadata(),
            record.systemMessage(), record.inputMessage(), record.modelResponses(),
            record.toolCallResponses(), record.taskContext(), record.failure(),
            record.timestamp());
    }

    /** A model call carrying the text it produced. */
    public static ModelResponse response(String content) {
        return new ModelResponse("", content, 0, 0, "", List.of());
    }

    /** The same call, with the reasoning the provider returned beside the answer. */
    public static ModelResponse thinking(ModelResponse call, String reasoning) {
        return new ModelResponse(call.id(), call.content(), call.inputTokenCount(),
            call.outputTokenCount(), reasoning, call.toolCalls());
    }

    /** The same call, with what the provider reported it spent. */
    public static ModelResponse costing(ModelResponse call, int input, int output) {
        return new ModelResponse(call.id(), call.content(), input, output, call.thinking(),
            call.toolCalls());
    }

    /** The same call, with the tools it invoked. */
    public static ModelResponse calling(ModelResponse call, ToolCall... tools) {
        return new ModelResponse(call.id(), call.content(), call.inputTokenCount(),
            call.outputTokenCount(), call.thinking(), List.of(tools));
    }

    /** A tool call naming a tool and nothing else. */
    public static ToolCall tool(String name) {
        return new ToolCall("", name, "", "");
    }

    /**
     * A tool call carrying arguments as a map.
     *
     * <p>{@link ToolCall#arguments()} is the JSON a provider returned. A scenario states its
     * expected arguments as a map, and {@link Arguments#render} writes the same JSON shape
     * {@link Arguments#parse} reads back.
     */
    public static ToolCall tool(String name, Map<String, String> arguments) {
        return new ToolCall("", name, Arguments.render(arguments), "");
    }

    /** The same tool call, with what the tool returned. */
    public static ToolCall returning(ToolCall call, String response) {
        return new ToolCall(call.id(), call.name(), call.arguments(), response);
    }

    /** A model call reported text and no usage, so a token total built on it is a floor. */
    public static boolean reportedUsage(ModelResponse call) {
        return call.inputTokenCount() > 0 || call.outputTokenCount() > 0;
    }

    /** The provider and model a record names, empty when the target reported neither. */
    public static Optional<ModelConfig> modelConfig(InteractionRecord record) {
        return Optional.ofNullable(record.metadata()).map(InteractionMetadata::modelConfig);
    }
}
