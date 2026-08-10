package io.akka.evalkit.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.ledger.LedgerClient;
import akka.javasdk.ledger.ToolCall;
import io.akka.evalkit.domain.Recording;
import io.akka.evalkit.metric.CitationFaithfulness;
import io.akka.evalkit.metric.Metric;

import java.util.List;

/**
 * Scores whether each citation marker in a reply points at a passage that carries the claim.
 *
 * <p>The passages are numbered before the judge sees them, so a {@code [2]} in the reply
 * resolves to a passage. A judge shown unnumbered passages answers about support rather than
 * about attribution.
 *
 * <p>Bind it in {@code application.conf}:
 *
 * <pre>{@code
 * akka.javasdk.evaluation.evaluators {
 *   evalkit-citation-faithfulness {
 *     enabled = true
 *     agents { my-agent { enabled = true, trigger = interaction } }
 *   }
 * }
 * }</pre>
 */
@Component(
    id = CitationFaithfulnessEvaluator.COMPONENT_ID,
    name = "Citation Faithfulness Evaluator",
    description = "Scores whether each citation marker points at a passage carrying the claim.")
public class CitationFaithfulnessEvaluator extends JudgedMetricEvaluator {

    public static final String COMPONENT_ID = "evalkit-citation-faithfulness";

    private static final String INSTRUCTIONS = """
        You judge whether each citation marker in a reply points at a passage that carries \
        the claim it is attached to.

        You will be given numbered passages and a reply containing markers of the form [N].

        Return one block per marker in the reply, in exactly this shape and nothing else:

        SUBJECT: the marker and the claim it is attached to
        VERDICT: yes if passage N carries that claim, no if it does not
        REASON: one sentence stating what decided it

        A marker pointing at a passage that is merely related does not carry the claim. \
        A marker whose number names no passage is not faithful.""";

    private final CitationFaithfulness metric;

    public CitationFaithfulnessEvaluator(LedgerClient ledger, ComponentClient componentClient) {
        this(ledger, componentClient, new CitationFaithfulness());
    }

    protected CitationFaithfulnessEvaluator(LedgerClient ledger, ComponentClient componentClient,
                                            CitationFaithfulness metric) {
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
    public String material(Recording recording) {
        List<String> passages = recording.toolsCalled().stream()
            .map(ToolCall::response)
            .filter(response -> response != null && !response.isBlank())
            .toList();
        String answered = recording.interaction().finalResponseText().strip();
        if (passages.isEmpty() || answered.isEmpty()) return "";
        return "Passages:\n\n" + CitationFaithfulness.numberPassages(passages)
            + "\n\nReply:\n\n" + answered;
    }
}
