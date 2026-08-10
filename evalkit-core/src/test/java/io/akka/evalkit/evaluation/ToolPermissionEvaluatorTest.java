package io.akka.evalkit.evaluation;

import akka.javasdk.ledger.EvaluationRecord;
import akka.javasdk.ledger.InteractionRecord;
import akka.javasdk.ledger.LedgerClient;
import akka.javasdk.ledger.ToolCall;
import io.akka.evalkit.domain.RunOutcome;
import io.akka.evalkit.ledger.Interactions;
import io.akka.evalkit.metric.ToolPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deterministic evaluator against a recorded interaction.
 *
 * <p>The evaluator reads the tools from the record rather than from a scenario, which is what
 * lets it score traffic no campaign caused.
 */
@DisplayName("ToolPermissionEvaluator · a policy against the tools a record names")
class ToolPermissionEvaluatorTest {

    private static InteractionRecord recordCalling(String... tools) {
        var call = Interactions.calling(Interactions.response("done"),
            java.util.Arrays.stream(tools).map(Interactions::tool).toArray(ToolCall[]::new));
        var built = Interactions.of("session-1", "", "cancel my order",
            tools.length == 0 ? List.of(Interactions.response("done")) : List.of(call),
            Optional.empty(), Optional.empty());
        return new InteractionRecord("interaction-1", built.sessionId(), "orders-agent",
            built.flowId(), built.metadata(), built.systemMessage(), built.inputMessage(),
            built.modelResponses(), built.toolCallResponses(), built.taskContext(),
            built.failure(), built.timestamp());
    }

    private static LedgerClient ledgerOf(InteractionRecord record) {
        return new LedgerClient() {
            @Override
            public InteractionRecord getInteraction(String interactionId) {
                return record;
            }

            @Override
            public CompletionStage<InteractionRecord> getInteractionAsync(String id) {
                return java.util.concurrent.CompletableFuture.completedFuture(record);
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

    private static RunOutcome score(ToolPermission policy, String... called) {
        var record = recordCalling(called);
        var evaluator = new ToolPermissionEvaluator(ledgerOf(record), policy);
        return evaluator.scorer().score(evaluator.recordingOf(record));
    }

    @Test
    @DisplayName("a run that called only allowed tools passes")
    void anAuthorisedRunPasses() {
        var outcome = score(ToolPermission.allowing("search_kb"), "search_kb");

        assertThat(outcome.passed()).isTrue();
        assertThat(outcome).isInstanceOf(RunOutcome.Measured.class);
    }

    /**
     * The case this evaluator is known to catch.
     *
     * <p>A policy that read no tools from the record would score every run 1 and report a
     * clean campaign against an agent calling anything it liked.
     */
    @Test
    @DisplayName("a run that called a tool outside the policy fails and names it")
    void anUnauthorisedCallFails() {
        var outcome = score(ToolPermission.allowing("search_kb"), "search_kb", "delete_account");

        assertThat(outcome.passed()).isFalse();
        assertThat(outcome.describe()).contains("tool-permission");
    }

    @Test
    @DisplayName("the tools are read from the record rather than from a scenario")
    void theToolsComeFromTheRecord() {
        var record = recordCalling("search_kb", "delete_account");
        var evaluator = new ToolPermissionEvaluator(
            ledgerOf(record), ToolPermission.allowing("search_kb"));

        assertThat(evaluator.recordingOf(record).toolNames())
            .containsExactly("search_kb", "delete_account");
    }

    /** A metric reads what the run did, so no scenario supplies an expected outcome. */
    @Test
    @DisplayName("a metric evaluator reads no expected outcome")
    void aMetricEvaluatorReadsNoExpectedOutcome() {
        var record = recordCalling("search_kb");
        var evaluator = new ToolPermissionEvaluator(
            ledgerOf(record), ToolPermission.allowing("search_kb"));

        assertThat(evaluator.recordingOf(record).transcript().expectedOutcome()).isEmpty();
    }
}
