package io.akka.evalkit.evaluation;

import akka.javasdk.annotations.Component;
import akka.javasdk.ledger.LedgerClient;
import io.akka.evalkit.domain.Recording;
import io.akka.evalkit.metric.Judgement;
import io.akka.evalkit.metric.Metric;
import io.akka.evalkit.metric.ToolPermission;

import java.util.List;

/**
 * Scores every tool an agent called against a policy naming what it may call.
 *
 * <p>No model is called. The policy names the tools and the record names the calls, so a
 * campaign of these costs nothing and returns the same answer every run.
 *
 * <p>Bind it to an agent in {@code application.conf}:
 *
 * <pre>{@code
 * akka.javasdk.evaluation.evaluators {
 *   evalkit-tool-permission {
 *     enabled = true
 *     agents { my-agent { enabled = true, trigger = interaction } }
 *   }
 * }
 * }</pre>
 *
 * <p>A run that called no tool produces no judgement, and {@link ToolPermission} scores an
 * empty list 1. A policy holds over a run that called nothing, because nothing unauthorised
 * happened.
 */
@Component(
    id = ToolPermissionEvaluator.COMPONENT_ID,
    name = "Tool Permission Evaluator",
    description = "Scores the tools an agent called against the policy naming what it may call.")
public class ToolPermissionEvaluator extends MetricEvaluator {

    public static final String COMPONENT_ID = "evalkit-tool-permission";

    private final ToolPermission policy;

    protected ToolPermissionEvaluator(LedgerClient ledger, ToolPermission policy) {
        super(ledger);
        this.policy = policy;
    }

    @Override
    protected Metric metric() {
        return policy;
    }

    @Override
    protected List<Judgement> judge(Recording recording) {
        return policy.judge(recording.toolNames());
    }
}
