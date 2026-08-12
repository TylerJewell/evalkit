package io.akka.evalkit.application;

import akka.javasdk.ledger.EvaluationRecord;
import akka.javasdk.ledger.InteractionRecord;
import akka.javasdk.ledger.LedgerClient;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import io.akka.evalkit.domain.InconclusiveScore;
import io.akka.evalkit.domain.Observation;
import io.akka.evalkit.domain.RunOutcome;
import io.akka.evalkit.ledger.Interactions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three model-scored metric evaluators, with no model behind them.
 *
 * <p>Each one reads a recorded interaction and asks a judge about every subject in it. What
 * is fixed here is the part that does not involve a model: which runs are worth asking about,
 * how a reply is read, and what a run with nothing to judge reports.
 */
@DisplayName("Judged metric evaluators · a metric asking a model about a recorded run")
class JudgedMetricEvaluatorTest extends TestKitSupport {

    private final TestModelProvider model = new TestModelProvider();

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withModelProvider(MetricJudge.class, model);
    }

    private static InteractionRecord recordWith(String answer, String... toolResponses) {
        var tools = java.util.Arrays.stream(toolResponses)
            .map(response -> Interactions.returning(
                Interactions.tool("search_kb", Map.of("query", "refund")), response))
            .toArray(akka.javasdk.ledger.ToolCall[]::new);
        var call = tools.length == 0
            ? Interactions.response(answer)
            : Interactions.calling(Interactions.response(answer), tools);
        return Interactions.identified(
            Interactions.of("session-1", "be helpful", "when do I get my refund?",
                List.of(call), Optional.empty(), Optional.empty()),
            "interaction-1");
    }

    private LedgerClient ledgerOf(InteractionRecord record) {
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

    @Test
    @DisplayName("turn relevancy asks about the exchange and reads the verdict")
    void turnRelevancyScoresTheExchange() {
        model.fixedResponse("""
            SUBJECT: when the refund arrives
            VERDICT: yes
            REASON: the reply states 30 days
            """);
        var record = recordWith("the refund takes 30 days");
        var evaluator = new TurnRelevancyEvaluator(ledgerOf(record), componentClient);

        var outcome = evaluator.score(record);

        assertThat(outcome).isInstanceOf(RunOutcome.Measured.class);
        assertThat(outcome.passed()).isTrue();
    }

    @Test
    @DisplayName("turn faithfulness scores the share of claims the passages support")
    void turnFaithfulnessScoresTheClaims() {
        model.fixedResponse("""
            SUBJECT: the window is 30 days
            VERDICT: yes
            REASON: the passage states it

            SUBJECT: shipping is refunded
            VERDICT: no
            REASON: no passage mentions shipping
            """);
        var record = recordWith("Refunds take 30 days and shipping is refunded.",
            "the refund window is 30 days");
        var evaluator = new TurnFaithfulnessEvaluator(ledgerOf(record), componentClient);

        var outcome = evaluator.score(record);

        assertThat(outcome).isInstanceOf(RunOutcome.Measured.class);
        assertThat(((RunOutcome.Measured) outcome).value()).isEqualTo(0.5);
    }

    /**
     * The case the material check is known to catch.
     *
     * <p>A run that retrieved nothing has nothing for a claim to be faithful to. Asking a
     * model anyway would spend a call to be told what the record already says.
     */
    @Test
    @DisplayName("faithfulness calls no model when the run retrieved nothing")
    void faithfulnessCallsNoModelWithoutPassages() {
        model.fixedResponse("SUBJECT: nothing\nVERDICT: no");
        var record = recordWith("Refunds take 30 days.");
        var evaluator = new TurnFaithfulnessEvaluator(ledgerOf(record), componentClient);

        assertThat(evaluator.material(evaluator.recordingOf(record))).isEmpty();
    }

    @Test
    @DisplayName("citation faithfulness numbers the passages before the judge sees them")
    void citationFaithfulnessNumbersThePassages() {
        var record = recordWith("The window is 30 days [1].", "the refund window is 30 days");
        var evaluator = new CitationFaithfulnessEvaluator(ledgerOf(record), componentClient);

        assertThat(evaluator.material(evaluator.recordingOf(record)))
            .contains("[1] the refund window is 30 days");
    }

    @Test
    @DisplayName("citation faithfulness scores a marker that names the wrong passage")
    void citationFaithfulnessScoresAWrongMarker() {
        model.fixedResponse("""
            SUBJECT: [1] the window is 60 days
            VERDICT: no
            REASON: passage 1 states 30 days
            """);
        var record = recordWith("The window is 60 days [1].", "the refund window is 30 days");
        var evaluator = new CitationFaithfulnessEvaluator(ledgerOf(record), componentClient);

        var outcome = evaluator.score(record);

        assertThat(outcome.passed()).isFalse();
    }

    /**
     * A judge that answered unreadably is absent evidence, never a score.
     *
     * <p>Reading it as an empty finding list would score the metric 1 and report a clean
     * run on the strength of a reply nobody could parse.
     */
    @Test
    @DisplayName("a reply the parser cannot read reaches no verdict")
    void anUnreadableReplyReachesNoVerdict() {
        model.fixedResponse("I am not able to assess this conversation.");
        var record = recordWith("the refund takes 30 days");
        var evaluator = new TurnRelevancyEvaluator(ledgerOf(record), componentClient);

        assertThatThrownBy(() -> evaluator.score(record))
            .isInstanceOf(InconclusiveScore.class);
    }

    /** A metric reads what the run did, so no scenario states an expected outcome. */
    @Test
    @DisplayName("a judged metric evaluator reads no expected outcome")
    void aJudgedEvaluatorReadsNoExpectedOutcome() {
        var record = recordWith("the refund takes 30 days");
        var evaluator = new TurnRelevancyEvaluator(ledgerOf(record), componentClient);

        assertThat(evaluator.recordingOf(record).transcript().expectedOutcome()).isEmpty();
    }
}
