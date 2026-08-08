package io.akka.evalkit.domain;

import java.util.Map;

/**
 * A tool the system under test invoked while answering.
 *
 * <p>Arguments are strings whatever the tool's own types are. A scorer compares what was
 * sent, and rendering a number as {@code "3"} on both sides compares equal without putting
 * a JSON library in the path of a module that declares no dependencies.
 *
 * @param name      the tool as the runtime named it, prefixed as the model saw it
 * @param arguments what was passed, empty when the target reports the call and not its
 *                  arguments
 */
public record ToolCall(String name, Map<String, String> arguments) {

    public ToolCall {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("tool name required");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }

    public static ToolCall named(String name) {
        return new ToolCall(name, Map.of());
    }
}
