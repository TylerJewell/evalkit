package io.akka.evalkit.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.ledger.LedgerClient;
import io.akka.evalkit.domain.Observation;
import io.akka.evalkit.metric.Metric;
import io.akka.evalkit.metric.TurnRelevancy;

/**
 * Scores how much of a conversation answered what the user asked.
 *
 * <p>Reads the interaction and no expected outcome, so it scores live traffic as readily as a
 * campaign. Bind it in {@code application.conf}:
 *
 * <pre>{@code
 * akka.javasdk.evaluation.evaluators {
 *   evalkit-turn-relevancy {
 *     enabled = true
 *     agents { my-agent { enabled = true, trigger = interaction } }
 *   }
 * }
 * }</pre>
 */
@Component(
    id = TurnRelevancyEvaluator.COMPONENT_ID,
    name = "Turn Relevancy Evaluator",
    description = "Scores the share of exchanges in a run that answered what the user asked.")
public class TurnRelevancyEvaluator extends JudgedMetricEvaluator {

    public static final String COMPONENT_ID = "evalkit-turn-relevancy";

    private static final String INSTRUCTIONS = """
        You judge whether an assistant's reply answered what the user asked.

        You will be given one exchange between a user and an assistant.

        Return one block, in exactly this shape and nothing else:

        SUBJECT: <a short description of what the user asked>
        VERDICT: yes if the reply answered it, no if it did not
        REASON: one sentence stating what decided it

        Judge only whether the reply addressed the question. A reply that is wrong on the \
        facts but answers the question is relevant.""";

    private final TurnRelevancy metric;

    public TurnRelevancyEvaluator(LedgerClient ledger, ComponentClient componentClient) {
        this(ledger, componentClient, new TurnRelevancy());
    }

    protected TurnRelevancyEvaluator(LedgerClient ledger, ComponentClient componentClient,
                                     TurnRelevancy metric) {
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

    /** The exchange, which is the question the user asked and the answer that came back. */
    @Override
    public String material(Observation observation) {
        String asked = observation.interaction().inputText().strip();
        String answered = observation.interaction().finalResponseText().strip();
        if (asked.isEmpty() || answered.isEmpty()) return "";
        return "User: " + asked + "\n\nAssistant: " + answered;
    }
}
