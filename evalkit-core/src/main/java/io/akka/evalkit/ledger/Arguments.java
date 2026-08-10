package io.akka.evalkit.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.evalkit.domain.NoVerdict;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the arguments a recorded tool call carries.
 *
 * <p>{@link akka.javasdk.ledger.ToolCall#arguments()} is the JSON string a provider returned.
 * {@link io.akka.evalkit.metric.ToolCorrectness} credits a call by the share of keys both
 * sides name with the same value, and a string has no keys, so a comparison parses first.
 *
 * <p>A string that does not parse produces {@link NoVerdict}. Scoring it zero would report a
 * call made with wrong arguments, and the failure is in the parse.
 *
 * <p>Values are read as text. A number, a boolean and a nested object each become the
 * characters that spell them, so two calls agree when their JSON agrees.
 */
public final class Arguments {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Arguments() {}

    /** The arguments, or {@link NoVerdict} when the string is not a JSON object. */
    public static Map<String, String> parse(String json) {
        return read(json).orElseThrow(() -> new NoVerdict(
            "tool arguments are not readable as JSON: " + json));
    }

    /**
     * The arguments, or empty when the string is not a JSON object.
     *
     * <p>A null or blank string is the absence of a record rather than a malformed one, so it
     * reads as no arguments.
     */
    public static Optional<Map<String, String>> read(String json) {
        if (json == null || json.isBlank()) return Optional.of(Map.of());

        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) return Optional.empty();

        var out = new LinkedHashMap<String, String>();
        root.properties().forEach(field -> out.put(field.getKey(), text(field.getValue())));
        return Optional.of(out);
    }

    /** The JSON an object with these members spells, for a scenario stating what it expects. */
    public static String render(Map<String, String> arguments) {
        if (arguments == null || arguments.isEmpty()) return "{}";
        try {
            return JSON.writeValueAsString(arguments);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("arguments cannot be written as JSON", e);
        }
    }

    private static String text(JsonNode value) {
        return value.isValueNode() ? value.asText() : value.toString();
    }
}
