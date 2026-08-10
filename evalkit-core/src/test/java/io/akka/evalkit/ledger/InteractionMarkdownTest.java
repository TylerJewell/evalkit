package io.akka.evalkit.ledger;

import akka.javasdk.ledger.Failure;
import akka.javasdk.ledger.InteractionRecord;
import akka.javasdk.ledger.ModelResponse;
import akka.javasdk.ledger.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The markdown a recorded corpus is stored as.
 *
 * <p>A corpus is edited by hand for years after the run that produced it, so the round trip
 * has to survive a person rewriting a transcript in place.
 */
@DisplayName("InteractionMarkdown · a recorded interaction a person can edit")
class InteractionMarkdownTest {

    private static InteractionRecord record() {
        var tool = Interactions.returning(
            Interactions.tool("search_kb", Map.of("query", "return window")),
            "30-day return window");
        var call = new ModelResponse("", "Our return window is 30 days.", 1204, 38,
            "The order is outside the window.", List.of(tool));
        return new InteractionRecord("refund-outside-window-01", "session-42", "refund-agent",
            Optional.of("wave-3"), new akka.javasdk.ledger.InteractionMetadata(
                null, Map.of(), Instant.parse("2026-08-09T21:02:10Z"),
                Instant.parse("2026-08-09T21:02:11.400Z"),
                akka.javasdk.ledger.InteractionMetadata.FinishReason.UNSPECIFIED),
            "You are a refund assistant.",
            List.of(new akka.javasdk.agent.MessageContent.TextMessageContent(
                "It has been 45 days. Can I still return it?")),
            List.of(call), List.of(), Optional.empty(), Optional.empty(),
            Instant.parse("2026-08-09T21:02:10Z"));
    }

    @Test
    @DisplayName("a rendered record parses back to the same interaction")
    void theRoundTripHolds() {
        var original = record();

        var read = InteractionMarkdown.parse(InteractionMarkdown.render(original));

        assertThat(read.interactionId()).isEqualTo("refund-outside-window-01");
        assertThat(read.sessionId()).isEqualTo("session-42");
        assertThat(read.agentComponentId()).isEqualTo("refund-agent");
        assertThat(read.flowId()).contains("wave-3");
        assertThat(read.systemMessage()).isEqualTo("You are a refund assistant.");
        assertThat(read.inputText()).isEqualTo("It has been 45 days. Can I still return it?");
        assertThat(read.finalResponseText()).isEqualTo("Our return window is 30 days.");
        assertThat(read.totalInputTokens()).isEqualTo(1204);
        assertThat(read.totalOutputTokens()).isEqualTo(38);
        assertThat(read.timestamp()).isEqualTo(Instant.parse("2026-08-09T21:02:10Z"));
    }

    @Test
    @DisplayName("a tool call keeps its name, its arguments and what it returned")
    void aToolCallSurvivesTheRoundTrip() {
        var read = InteractionMarkdown.parse(InteractionMarkdown.render(record()));

        assertThat(read.toolCalls()).hasSize(1);
        ToolCall tool = read.toolCalls().get(0);
        assertThat(tool.name()).isEqualTo("search_kb");
        assertThat(Arguments.parse(tool.arguments())).containsEntry("query", "return window");
        assertThat(tool.response()).isEqualTo("30-day return window");
    }

    @Test
    @DisplayName("the reasoning a plan is read from survives the round trip")
    void theReasoningSurvives() {
        var read = InteractionMarkdown.parse(InteractionMarkdown.render(record()));

        assertThat(read.modelResponses().get(0).thinking())
            .isEqualTo("The order is outside the window.");
    }

    @Test
    @DisplayName("the id is a field, so renaming the file does not rename the interaction")
    void theIdIsAField() {
        assertThat(InteractionMarkdown.render(record()))
            .contains("- id: refund-outside-window-01");
    }

    @Test
    @DisplayName("a run that ended in a failure records why")
    void aFailureSurvives() {
        var built = Interactions.of("session-1", "", "when do I get my refund?", List.of(),
            Optional.empty(),
            Optional.of(new Failure(Failure.FailureReason.TIMEOUT, "the provider did not answer")));

        var read = InteractionMarkdown.parse(InteractionMarkdown.render(built));

        assertThat(read.failure()).isPresent();
        assertThat(read.failure().orElseThrow().reason())
            .isEqualTo(Failure.FailureReason.TIMEOUT);
        assertThat(read.failure().orElseThrow().description())
            .isEqualTo("the provider did not answer");
    }

    @Test
    @DisplayName("several model calls keep their order")
    void severalModelCallsKeepTheirOrder() {
        var built = Interactions.of("session-1", "", "book me a table",
            List.of(Interactions.response("Looking."), Interactions.response("Booked for 8pm.")),
            Optional.empty(), Optional.empty());

        var read = InteractionMarkdown.parse(InteractionMarkdown.render(built));

        assertThat(read.modelResponses()).hasSize(2);
        assertThat(read.modelResponses().get(0).content()).isEqualTo("Looking.");
        assertThat(read.finalResponseText()).isEqualTo("Booked for 8pm.");
    }

    /**
     * The case the format is known to catch.
     *
     * <p>A transcript is prose, and prose contains markdown. A parser that ended a section at
     * any {@code #} would silently truncate a reply that quoted a heading, and the run would
     * be scored on half an answer.
     */
    @Test
    @DisplayName("a reply containing a heading and a fence survives whole")
    void aReplyCarryingMarkdownSurvives() {
        String awkward = "# Refund policy\n\nRun this:\n\n```\nrefund --order 4417\n```\n\nDone.";
        var built = Interactions.of("session-1", "", "how do I refund?",
            List.of(Interactions.response(awkward)), Optional.empty(), Optional.empty());

        var read = InteractionMarkdown.parse(InteractionMarkdown.render(built));

        assertThat(read.finalResponseText()).isEqualTo(awkward);
    }

    /** A tool call whose arguments carry a fence cannot close the fence that holds them. */
    @Test
    @DisplayName("tool arguments containing a fence survive whole")
    void argumentsCarryingAFenceSurvive() {
        var tool = Interactions.returning(
            Interactions.tool("run", Map.of("script", "```\nls\n```")), "ok");
        var call = Interactions.calling(Interactions.response("done"), tool);
        var built = Interactions.of("session-1", "", "run it", List.of(call),
            Optional.empty(), Optional.empty());

        var read = InteractionMarkdown.parse(InteractionMarkdown.render(built));

        assertThat(Arguments.parse(read.toolCalls().get(0).arguments()))
            .containsEntry("script", "```\nls\n```");
    }
}
