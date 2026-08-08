package io.akka.evalkit.metric.dag;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A decision graph that scores a run by the branch the judge chose.
 *
 * <p>Model calls happen at the judgement nodes and nowhere else. A graph whose branches
 * are decided early can reach a score after one call, where a metric that scores the whole
 * reply pays for the whole reply every time.
 *
 * <p><b>A node reached by two parents is evaluated once.</b> The legacy graph in DeepEval's
 * own fixture hangs one ordering node under both a task node and a judgement node, and
 * asks its model exactly one question per node type. Evaluating a shared node twice would
 * double the cost and let one run answer the same question two different ways.
 *
 * <p>Ported from DeepEval's DAG metric, Apache 2.0. Verified by
 * {@code DagConformanceTest} against {@code tests/test_metrics/test_dag.py} at commit
 * bd10fa6.
 */
public final class Dag {

    private final DagNode root;

    private Dag(DagNode root) {
        this.root = root;
    }

    /**
     * A graph with one entry point.
     *
     * <p>DeepEval accepts several roots. How it combines their scores is not pinned by any
     * test there, so this port takes one root and refuses the rest instead of guessing at
     * behaviour nobody has checked.
     */
    public static Dag rootedAt(DagNode root) {
        if (root == null) throw new IllegalArgumentException("a graph needs a root");
        return new Dag(root);
    }

    /** What a traversal reached, and how it got there. */
    public record Outcome(double score, List<String> path, String stoppedBecause) {

        public Outcome {
            path = List.copyOf(path);
        }

        public boolean reachedAScore() {
            return stoppedBecause == null;
        }
    }

    /**
     * Walks the graph and returns the score of the verdict it reached.
     *
     * <p>Verdict scores run from 0 to 10 in the graph and are reported from 0 to 1, which
     * is the range every metric reports.
     */
    public Outcome traverse(DagJudge judge) {
        var evidence = new LinkedHashMap<String, List<String>>();
        var visited = new IdentityHashMap<DagNode, Boolean>();
        var path = new java.util.ArrayList<String>();
        return walk(root, judge, evidence, visited, path);
    }

    private Outcome walk(DagNode node, DagJudge judge, Map<String, List<String>> evidence,
                         Map<DagNode, Boolean> visited, List<String> path) {

        if (visited.putIfAbsent(node, Boolean.TRUE) != null) {
            return stopped(path, "revisited a node already evaluated on this path");
        }

        return switch (node) {

            case DagNode.TaskNode task -> {
                evidence.put(task.outputLabel(), List.copyOf(judge.extract(task, evidence)));
                path.add("task:" + task.outputLabel());
                // A task node scores nothing. The first child that reaches a score is the
                // score, which is what makes a shared child worth visiting only once.
                Outcome reached = null;
                for (DagNode child : task.children()) {
                    Outcome outcome = walk(child, judge, evidence, visited, path);
                    if (outcome.reachedAScore() && reached == null) reached = outcome;
                }
                yield reached != null ? reached
                    : stopped(path, "no child of " + task.outputLabel() + " reached a score");
            }

            case DagNode.BinaryJudgementNode binary -> {
                boolean verdict = judge.decide(binary, evidence);
                path.add("binary:" + verdict);
                yield follow(binary.children(), verdict, judge, evidence, visited, path);
            }

            case DagNode.NonBinaryJudgementNode choice -> {
                String verdict = judge.classify(choice, evidence);
                path.add("choice:" + verdict);
                if (!choice.options().contains(verdict)) {
                    yield stopped(path, "judge returned '" + verdict
                        + "', which is not one of " + choice.options());
                }
                yield follow(choice.children(), verdict, judge, evidence, visited, path);
            }

            case DagNode.VerdictNode verdict -> score(verdict, judge, evidence, visited, path);
        };
    }

    private Outcome follow(List<DagNode.VerdictNode> children, Object verdict, DagJudge judge,
                           Map<String, List<String>> evidence, Map<DagNode, Boolean> visited,
                           List<String> path) {
        for (DagNode.VerdictNode child : children) {
            if (child.verdict().equals(verdict)) {
                return score(child, judge, evidence, visited, path);
            }
        }
        return stopped(path, "no verdict registered for " + verdict);
    }

    private Outcome score(DagNode.VerdictNode verdict, DagJudge judge,
                          Map<String, List<String>> evidence, Map<DagNode, Boolean> visited,
                          List<String> path) {
        if (verdict.score().isPresent()) {
            return new Outcome(verdict.score().getAsInt() / 10.0, path, null);
        }
        return walk(verdict.child().orElseThrow(), judge, evidence, visited, path);
    }

    private static Outcome stopped(List<String> path, String because) {
        return new Outcome(0.0, path, because);
    }
}
