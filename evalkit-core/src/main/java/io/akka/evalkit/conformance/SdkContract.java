package io.akka.evalkit.conformance;

import java.util.List;

/**
 * The Akka SDK surface this kit is shaped against, and how confident each entry is.
 *
 * <p>{@code akka.javasdk.evaluation} and {@code akka.javasdk.ledger} ship on a branch that
 * has not merged. These types were read from source at the commit below and evalkit's own
 * records were shaped to match them, so that importing the real ones later is a field copy
 * rather than a redesign.
 *
 * <p><b>Why write the assumptions down.</b> A shape copied from a branch is a guess about a
 * moving target: the branch sits behind its main, a merge is coming, and any of these fields
 * can be renamed before it lands. Recorded, a wrong assumption becomes a failing test the
 * moment the jars are on a classpath. Unrecorded, it becomes a puzzling compile error weeks
 * later with nothing to say what was expected.
 *
 * <p>This carries no dependency and no behaviour &mdash; it is a list of names, kept in main
 * sources so that {@code evalkit-akka} can check it against the real classes. The half that
 * can be checked without them is in {@code SdkContractTest}.
 */
public final class SdkContract {

    public static final String REPO = "akka/akka-sdk";
    public static final String BRANCH = "feature/governance";
    public static final String COMMIT = "7321c44b406d4db70462e247905b09e308bc61e0";

    /** How sure this kit is that the entry matches the real type. */
    public enum Confidence {
        /** The declaration was read in full: every member below appears in the source. */
        READ,
        /** Only the class documentation was read. The members are inferred and may be wrong. */
        INFERRED
    }

    /**
     * @param type       the fully qualified SDK type
     * @param members    fields, methods or nested types this kit depends on
     * @param confidence whether the members were read or inferred
     * @param shapes     the evalkit type shaped to match it, or empty when nothing does yet
     */
    public record Entry(String type, List<String> members, Confidence confidence, String shapes) {}

    public static final List<Entry> ENTRIES = List.of(

        new Entry("akka.javasdk.evaluation.Evaluation",
            List.of("passed", "explanation", "score", "label", "attributes"),
            Confidence.READ, "io.akka.evalkit.domain.Verdict"),

        new Entry("akka.javasdk.evaluation.Evaluator",
            List.of("evaluate", "effects"),
            Confidence.READ, ""),

        new Entry("akka.javasdk.evaluation.EvaluationContext",
            List.of("subject", "evaluationId"),
            Confidence.READ, ""),

        new Entry("akka.javasdk.evaluation.Subject",
            List.of("agentComponentId", "interactionId", "FlowInteraction", "AgentInteraction"),
            Confidence.READ, ""),

        // The handler is onEvaluation, not evaluate as Evaluator has it, and the class
        // carries state: WorkflowEvaluator<S> with emptyState and a Settings holding the
        // evaluation timeout, the step timeout and the retry ceiling. Nothing here is
        // shaped after it yet. CampaignWorkflow is the nearest thing and it predates this
        // type, so claiming the shape would record an intention as a fact.
        new Entry("akka.javasdk.evaluation.WorkflowEvaluator",
            List.of("onEvaluation", "emptyState", "settings", "Effect", "Settings"),
            Confidence.READ, ""),

        new Entry("akka.javasdk.ledger.InteractionRecord",
            List.of("interactionId", "sessionId", "agentComponentId", "flowId", "metadata",
                "systemMessage", "inputMessage", "modelResponses", "toolCallResponses",
                "taskContext", "failure", "timestamp", "inputText", "finalResponseText",
                "toolCalls", "transcript"),
            Confidence.READ, "io.akka.evalkit.domain.Evidence"),

        new Entry("akka.javasdk.ledger.ModelResponse",
            List.of("id", "content", "inputTokenCount", "outputTokenCount", "thinking"),
            Confidence.READ, "io.akka.evalkit.domain.ModelCall"),

        new Entry("akka.javasdk.ledger.ToolCall",
            List.of("id", "name", "arguments", "response"),
            Confidence.READ, "io.akka.evalkit.domain.ToolCall"),

        new Entry("akka.javasdk.ledger.Failure",
            List.of("reason", "description", "FailureReason"),
            Confidence.READ, "io.akka.evalkit.domain.Failure"),

        new Entry("akka.javasdk.ledger.EvaluationRecord",
            List.of("Outcome", "Trigger", "evaluations"),
            Confidence.READ, "io.akka.evalkit.domain.RunOutcome"),

        new Entry("akka.javasdk.ledger.LedgerClient",
            List.of("getInteraction", "getInteractionAsync", "getEvaluation",
                "getEvaluationAsync"),
            Confidence.READ, ""),

        new Entry("akka.javasdk.ledger.InteractionMetadata",
            List.of("modelConfig", "modelConfigMap", "callStartedAt", "callFinishedAt",
                "FinishReason"),
            Confidence.READ, ""),

        new Entry("akka.javasdk.ledger.ModelConfig",
            List.of("providerName", "modelName", "baseUrl", "temperature", "topP", "topK"),
            Confidence.READ, ""),

        new Entry("akka.javasdk.ledger.TaskContext",
            List.of("agentInstanceId", "taskId", "taskName", "taskDescription",
                "taskResultType", "iterationNumber"),
            Confidence.READ, ""));

    /** Entries claiming a member list without having read the declaration that supplies it. */
    public static List<String> inferred(List<Entry> entries) {
        return entries.stream()
            .filter(entry -> entry.confidence() == Confidence.INFERRED)
            .map(Entry::type)
            .toList();
    }

    /** Entries naming no member, which assert nothing and would verify nothing. */
    public static List<String> empty(List<Entry> entries) {
        return entries.stream()
            .filter(entry -> entry.members().isEmpty())
            .map(Entry::type)
            .toList();
    }

    private SdkContract() {}
}
