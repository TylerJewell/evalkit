package io.akka.evalkit.ledger;

import akka.javasdk.agent.MessageContent;
import akka.javasdk.ledger.Failure;
import akka.javasdk.ledger.InteractionMetadata;
import akka.javasdk.ledger.InteractionRecord;
import akka.javasdk.ledger.ModelResponse;
import akka.javasdk.ledger.ToolCall;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An {@link InteractionRecord} as markdown a person can read and edit.
 *
 * <p>A recorded dataset is checked into a repository and edited by hand for years after the
 * run that produced it. The shape below puts the conversation in prose sections and the
 * figures in list items, so a reviewer reads a transcript rather than a serialisation.
 *
 * <pre>{@code
 * # Interaction
 *
 * - id: refund-outside-window-01
 * - session: session-42
 * - agent: refund-agent
 * - recorded: 2026-08-09T21:02:10Z
 * - latency: PT1.4S
 *
 * ## System
 *
 * You are a refund assistant.
 *
 * ## User
 *
 * It has been 45 days. Can I still return it?
 *
 * ## Model call
 *
 * ### Thinking
 *
 * The order is outside the window.
 *
 * ### Tool search_kb
 *
 * ```arguments
 * {"query":"return window"}
 * ```
 *
 * ```response
 * 30-day return window
 * ```
 *
 * ### Reply
 *
 * Our return window is 30 days, so this order sits outside it.
 *
 * ### Tokens
 *
 * - input: 1204
 * - output: 38
 * }</pre>
 *
 * <p><b>The id is a field rather than the filename.</b> A file renamed while a dataset is
 * tidied would otherwise change the identity of the interaction it holds, and every
 * evaluation already recorded against that id would name something that no longer exists.
 *
 * <p>A prose section runs to the next heading this format names. A transcript containing a
 * line starting with {@code #} survives, unless that line reads exactly as one of the
 * headings below.
 */
public final class InteractionMarkdown {

    private static final String SYSTEM = "## System";
    private static final String USER = "## User";
    private static final String MODEL_CALL = "## Model call";
    private static final String FAILURE = "## Failure";
    private static final String THINKING = "### Thinking";
    private static final String REPLY = "### Reply";
    private static final String TOKENS = "### Tokens";
    private static final String TOOL = "### Tool ";

    private InteractionMarkdown() {}

    // ---- rendering ----

    /** The record as markdown, which {@link #parse} reads back. */
    public static String render(InteractionRecord record) {
        var out = new StringBuilder("# Interaction\n\n");
        // The id is always written, blank included. A dataset entry with no id is a dataset
        // entry no evaluation can name, and an empty field says so where a missing line
        // would read as a format this writer did not support.
        field(out, "id", record.interactionId());
        if (notEmpty(record.sessionId())) field(out, "session", record.sessionId());
        if (notEmpty(record.agentComponentId())) field(out, "agent", record.agentComponentId());
        record.flowId().ifPresent(flow -> field(out, "flow", flow));
        if (record.timestamp() != null && !record.timestamp().equals(Interactions.UNTIMED)) {
            field(out, "recorded", record.timestamp().toString());
        }
        latencyOf(record).ifPresent(elapsed -> field(out, "latency", elapsed.toString()));

        section(out, SYSTEM, record.systemMessage());
        section(out, USER, record.inputText());

        for (ModelResponse call : record.modelResponses()) {
            out.append('\n').append(MODEL_CALL).append("\n");
            if (notEmpty(call.thinking())) section(out, THINKING, call.thinking());
            for (ToolCall tool : call.toolCalls()) {
                out.append('\n').append(TOOL).append(tool.name()).append("\n\n");
                fence(out, "arguments", tool.arguments());
                out.append('\n');
                fence(out, "response", tool.response());
            }
            if (notEmpty(call.content())) section(out, REPLY, call.content());
            if (call.inputTokenCount() > 0 || call.outputTokenCount() > 0) {
                out.append('\n').append(TOKENS).append("\n\n");
                field(out, "input", Integer.toString(call.inputTokenCount()));
                field(out, "output", Integer.toString(call.outputTokenCount()));
            }
        }

        record.failure().ifPresent(failure -> {
            out.append('\n').append(FAILURE).append("\n\n");
            field(out, "reason", failure.reason().name());
            field(out, "description", failure.description());
        });

        return out.toString();
    }

    private static void field(StringBuilder out, String key, String value) {
        out.append("- ").append(key).append(": ").append(value == null ? "" : value).append('\n');
    }

    private static void section(StringBuilder out, String heading, String body) {
        out.append('\n').append(heading).append("\n\n").append(body == null ? "" : body.strip())
            .append('\n');
    }

    /** A fence long enough that the content cannot close it early. */
    private static void fence(StringBuilder out, String label, String content) {
        String body = content == null ? "" : content;
        String ticks = "```";
        while (body.contains(ticks)) ticks = ticks + "`";
        out.append(ticks).append(label).append('\n').append(body).append('\n')
            .append(ticks).append('\n');
    }

    private static boolean notEmpty(String text) {
        return text != null && !text.isEmpty();
    }

    private static Optional<Duration> latencyOf(InteractionRecord record) {
        var metadata = record.metadata();
        if (metadata == null
            || metadata.callStartedAt() == null
            || metadata.callFinishedAt() == null) {
            return Optional.empty();
        }
        var elapsed = Duration.between(metadata.callStartedAt(), metadata.callFinishedAt());
        return elapsed.isZero() ? Optional.empty() : Optional.of(elapsed);
    }

    // ---- parsing ----

    /** The record the markdown describes. */
    public static InteractionRecord parse(String markdown) {
        var lines = new Cursor(markdown == null ? "" : markdown);

        var header = lines.fields();
        String id = header.getOrDefault("id", "");
        String session = header.getOrDefault("session", "");
        String agent = header.getOrDefault("agent", "");
        Optional<String> flow = Optional.ofNullable(header.get("flow"));
        Instant recorded = instant(header.get("recorded"));
        Duration latency = duration(header.get("latency"));

        String system = "";
        String user = "";
        var calls = new ArrayList<ModelResponse>();
        Optional<Failure> failure = Optional.empty();

        while (!lines.done()) {
            String heading = lines.heading();
            if (heading == null) {
                lines.skip();
                continue;
            }
            if (heading.equals(SYSTEM)) {
                lines.skip();
                system = lines.prose();
            } else if (heading.equals(USER)) {
                lines.skip();
                user = lines.prose();
            } else if (heading.equals(MODEL_CALL)) {
                lines.skip();
                calls.add(modelCall(lines));
            } else if (heading.equals(FAILURE)) {
                lines.skip();
                var fields = lines.fields();
                failure = Optional.of(new Failure(
                    reason(fields.get("reason")), fields.getOrDefault("description", "")));
            } else {
                lines.skip();
            }
        }

        var metadata = new InteractionMetadata(null, Map.of(), recorded,
            latency == null ? recorded : recorded.plus(latency),
            InteractionMetadata.FinishReason.UNSPECIFIED);

        return new InteractionRecord(id, session, agent, flow, metadata, system,
            List.of(new MessageContent.TextMessageContent(user)), List.copyOf(calls),
            List.of(), Optional.empty(), failure, recorded);
    }

    private static ModelResponse modelCall(Cursor lines) {
        String thinking = "";
        String content = "";
        int input = 0;
        int output = 0;
        var tools = new ArrayList<ToolCall>();

        while (!lines.done()) {
            String heading = lines.heading();
            if (heading == null) {
                lines.skip();
                continue;
            }
            if (heading.equals(MODEL_CALL) || heading.equals(FAILURE)
                || heading.equals(SYSTEM) || heading.equals(USER)) {
                break;
            }
            if (heading.equals(THINKING)) {
                lines.skip();
                thinking = lines.prose();
            } else if (heading.equals(REPLY)) {
                lines.skip();
                content = lines.prose();
            } else if (heading.equals(TOKENS)) {
                lines.skip();
                var fields = lines.fields();
                input = number(fields.get("input"));
                output = number(fields.get("output"));
            } else if (heading.startsWith(TOOL)) {
                String name = heading.substring(TOOL.length()).strip();
                lines.skip();
                tools.add(new ToolCall("", name, lines.fence("arguments"), lines.fence("response")));
            } else {
                lines.skip();
            }
        }
        return new ModelResponse("", content, input, output, thinking, List.copyOf(tools));
    }

    private static Failure.FailureReason reason(String name) {
        if (name == null) return Failure.FailureReason.UNSPECIFIED;
        try {
            return Failure.FailureReason.valueOf(name.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return Failure.FailureReason.UNSPECIFIED;
        }
    }

    private static int number(String text) {
        if (text == null || text.isBlank()) return 0;
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static Instant instant(String text) {
        if (text == null || text.isBlank()) return Interactions.UNTIMED;
        try {
            return Instant.parse(text.strip());
        } catch (DateTimeParseException notAnInstant) {
            return Interactions.UNTIMED;
        }
    }

    private static Duration duration(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return Duration.parse(text.strip());
        } catch (DateTimeParseException notADuration) {
            return null;
        }
    }

    /** A cursor over the lines of one document. */
    private static final class Cursor {

        private final List<String> lines;
        private int at;

        Cursor(String text) {
            this.lines = List.of(text.replace("\r\n", "\n").split("\n", -1));
        }

        boolean done() {
            return at >= lines.size();
        }

        void skip() {
            at++;
        }

        /** The heading on the current line, or null when the line is not one this format names. */
        String heading() {
            if (done()) return null;
            String line = lines.get(at).strip();
            if (line.equals(SYSTEM) || line.equals(USER) || line.equals(MODEL_CALL)
                || line.equals(FAILURE) || line.equals(THINKING) || line.equals(REPLY)
                || line.equals(TOKENS) || line.startsWith(TOOL)) {
                return line;
            }
            return null;
        }

        /** The list items from here to the next heading or blank run that ends them. */
        Map<String, String> fields() {
            var out = new java.util.LinkedHashMap<String, String>();
            while (!done() && heading() == null) {
                String line = lines.get(at).strip();
                if (line.startsWith("- ")) {
                    int colon = line.indexOf(':');
                    if (colon > 2) {
                        out.put(line.substring(2, colon).strip(),
                            line.substring(colon + 1).strip());
                    }
                }
                at++;
            }
            return out;
        }

        /** The prose from here to the next heading this format names. */
        String prose() {
            var body = new StringBuilder();
            while (!done() && heading() == null) {
                body.append(lines.get(at)).append('\n');
                at++;
            }
            return body.toString().strip();
        }

        /**
         * The contents of the next fence carrying this label, before the next heading.
         *
         * <p>A tool call that recorded no response has no fence to read, and the empty string
         * it returns is the absence {@code ToolCorrectness} treats as unrecorded.
         */
        String fence(String label) {
            int from = at;
            while (!done() && heading() == null) {
                String line = lines.get(at).strip();
                if (line.startsWith("`") && line.endsWith(label) && line.contains("```")) {
                    String ticks = line.substring(0, line.length() - label.length());
                    at++;
                    var body = new StringBuilder();
                    boolean first = true;
                    while (!done() && !lines.get(at).strip().equals(ticks)) {
                        if (!first) body.append('\n');
                        body.append(lines.get(at));
                        first = false;
                        at++;
                    }
                    if (!done()) at++;
                    return body.toString();
                }
                at++;
            }
            at = from;
            return "";
        }
    }
}
