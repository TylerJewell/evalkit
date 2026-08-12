package io.akka.evalkit.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.ledger.LedgerClient;
import akka.javasdk.ledger.ToolCall;
import io.akka.evalkit.domain.Observation;
import io.akka.evalkit.metric.Metric;
import io.akka.evalkit.metric.TurnFaithfulness;

import java.util.stream.Collectors;

/**
 * Scores how much of a reply the passages behind it support.
 *
 * <p>The passages are what the tools returned. A run that called no tool retrieved nothing,
 * so there is nothing for a claim to be faithful to and no model is called.
 *
 * <p>Bind it in {@code application.conf}:
 *
 * <pre>{@code
 * akka.javasdk.evaluation.evaluators {
 *   evalkit-turn-faithfulness {
 *     enabled = true
 *     agents { my-agent { enabled = true, trigger = interaction } }
 *   }
 * }
 * }</pre>
 */
@Component(
    id = TurnFaithfulnessEvaluator.COMPONENT_ID,
    name = "Turn Faithfulness Evaluator",
    description = "Scores the share of claims in a reply that the retrieved passages support.")
public class TurnFaithfulnessEvaluator extends JudgedMetricEvaluator {

    public static final String COMPONENT_ID = "evalkit-turn-faithfulness";

    private static final String INSTRUCTIONS = """
        You judge whether each claim in an assistant's reply is supported by the passages \
        it retrieved.

        You will be given the retrieved passages and the reply.

        Break the reply into its separate factual claims. Return one block per claim, in \
        exactly this shape and nothing else:

        SUBJECT: <the claim, in your own words>
        VERDICT: yes if a passage supports it, no if none does
        REASON: one sentence naming the passage that decided it

        A claim the passages neither support nor contradict is not supported. Judge only \
        against the passages given, never against what you already know.""";

    private final TurnFaithfulness metric;

    public TurnFaithfulnessEvaluator(LedgerClient ledger, ComponentClient componentClient) {
        this(ledger, componentClient, new TurnFaithfulness());
    }

    protected TurnFaithfulnessEvaluator(LedgerClient ledger, ComponentClient componentClient,
                                        TurnFaithfulness metric) {
        super(ledger, componentClient);
        this.metric = metric;
    }

    @Override
    protected Metric metric() {
        return metric;
    }

    @Override
    protected String instructions() {
        return INSTRUCTIONS;
    }

    @Override
    public String material(Observation observation) {
        String passages = observation.toolsCalled().stream()
            .map(ToolCall::response)
            .filter(response -> response != null && !response.isBlank())
            .collect(Collectors.joining("\n\n"));
        String answered = observation.interaction().finalResponseText().strip();
        if (passages.isBlank() || answered.isEmpty()) return "";
        return "Passages:\n\n" + passages + "\n\nReply:\n\n" + answered;
    }
}
