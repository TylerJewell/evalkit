package io.akka.evalkit.application;

import akka.javasdk.agent.Agent;
import akka.javasdk.agent.MemoryProvider;
import akka.javasdk.annotations.AgentRole;
import akka.javasdk.annotations.Component;

/**
 * Asks a model to judge every subject in one run, and hands back what it said.
 *
 * <p>{@link ScenarioJudge} scores a whole transcript against an expected outcome and returns
 * one band. This one is asked about a list &mdash; every exchange, every claim, every
 * citation &mdash; and returns a block per subject that
 * {@link io.akka.evalkit.metric.JudgementReply} reads.
 *
 * <p>The prompt is not in this class. Each metric evaluator holds its own and passes it in,
 * so the agent is the mechanism and the prompt is the data.
 *
 * <p><b>Memory is off.</b> Judging is per-interaction and independent. A judge that
 * remembered the last twenty runs would drift toward its own recent answers, and two
 * campaigns run in a different order would disagree for no reason connected to the systems
 * being judged.
 */
@Component(
    id = "evalkit-metric-judge",
    name = "Metric Judge",
    description = """
        Judges every exchange, claim or citation in one recorded interaction, \
        returning a verdict and a reason for each.""")
@AgentRole("evaluator")
public class MetricJudge extends Agent {

    /**
     * @param instructions what the model is being asked to decide
     * @param material     the part of the run being judged
     */
    public record JudgeRequest(String instructions, String material) {}

    public Effect<String> judge(JudgeRequest request) {
        if (request == null || request.instructions() == null || request.material() == null) {
            return effects().error("instructions and material are required");
        }
        return effects()
            .systemMessage(request.instructions())
            .memory(MemoryProvider.none())
            .userMessage(request.material())
            .thenReply();
    }
}
