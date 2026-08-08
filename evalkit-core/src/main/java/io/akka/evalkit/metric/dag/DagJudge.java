package io.akka.evalkit.metric.dag;

import java.util.List;
import java.util.Map;

/**
 * Answers the questions a graph asks.
 *
 * <p>The only part of a DAG that reaches a model. Traversal, verdict matching and scoring
 * are pure and live in {@link Dag}, so the arithmetic runs under test with a scripted
 * implementation of this interface and no provider.
 *
 * @see Dag
 */
public interface DagJudge {

    /**
     * Structured evidence for a task node, stored under the node's output label.
     *
     * <p>Returning an empty list is a finding rather than an error. A summary with no
     * headings gives a heading extractor nothing, and the judgement nodes below decide
     * what that means.
     */
    List<String> extract(DagNode.TaskNode node, Map<String, List<String>> evidence);

    /** True or false for a binary criterion. */
    boolean decide(DagNode.BinaryJudgementNode node, Map<String, List<String>> evidence);

    /**
     * One of {@link DagNode.NonBinaryJudgementNode#options()}.
     *
     * <p>A label outside that set stops the traversal, because a graph cannot follow a
     * branch it does not have. {@link Dag} reports which label was returned.
     */
    String classify(DagNode.NonBinaryJudgementNode node, Map<String, List<String>> evidence);
}
