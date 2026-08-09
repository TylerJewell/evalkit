package io.akka.evalkit.domain;

import java.util.Map;

/**
 * A tool the system under test invoked while answering, and what it returned.
 *
 * <p>Arguments are strings whatever the tool's own types are. A scorer compares what was
 * sent, and rendering a number as {@code "3"} on both sides compares equal without putting
 * a JSON library in the path of a module that declares no dependencies.
 *
 * <p>Arguments are a map rather than the raw payload because
 * {@link io.akka.evalkit.metric.ToolCorrectness} scores the share of keys that agree, and a
 * call carrying three of four expected arguments is closer to right than one carrying none.
 * A single string cannot carry that difference.
 *
 * @param id        the runtime's id for the call, empty when it reported none
 * @param name      the tool as the runtime named it, prefixed as the model saw it
 * @param arguments what was passed, empty when the target reports the call and not its
 *                  arguments
 * @param response  what the tool returned, empty when the target did not record it. A tool
 *                  that was called and returned nothing is indistinguishable here from one
 *                  whose return went unrecorded, so a scorer comparing responses treats an
 *                  empty one as unrecorded rather than as an answer
 */
public record ToolCall(String id, String name, Map<String, String> arguments, String response) {

    public ToolCall {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name required");
        id = id == null ? "" : id;
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        response = response == null ? "" : response;
    }

    /** A call carrying what was sent and not what came back. */
    public ToolCall(String name, Map<String, String> arguments) {
        this("", name, arguments, "");
    }

    public static ToolCall named(String name) {
        return new ToolCall(name, Map.of());
    }

    /** The same call, with what the tool returned. */
    public ToolCall returning(String returned) {
        return new ToolCall(id, name, arguments, returned);
    }

    public boolean recordedResponse() {
        return !response.isEmpty();
    }
}
