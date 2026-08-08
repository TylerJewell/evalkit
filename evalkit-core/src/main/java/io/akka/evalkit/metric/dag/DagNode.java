package io.akka.evalkit.metric.dag;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * A step in a decision graph.
 *
 * <p>The model chooses which branch a run takes. The score attached to a branch is fixed
 * in code, so two runs that reach the same leaf score identically, and changing a score
 * is a change to the graph rather than to a prompt.
 *
 * <p>Ported from DeepEval's DAG metric, Apache 2.0.
 */
public sealed interface DagNode {

    /**
     * Extracts structured evidence and assigns no score.
     *
     * @param outputLabel names the evidence, so a later node can refer to it
     */
    record TaskNode(String instructions, String outputLabel, List<DagNode> children)
        implements DagNode {

        public TaskNode {
            require(instructions, "task instructions");
            require(outputLabel, "task output label");
            children = List.copyOf(children);
        }
    }

    /** Answers a criterion with true or false, then follows the matching verdict. */
    record BinaryJudgementNode(String criteria, List<VerdictNode> children) implements DagNode {

        public BinaryJudgementNode {
            require(criteria, "judgement criteria");
            children = List.copyOf(children);
            if (children.size() != 2) {
                throw new IllegalArgumentException(
                    "a binary judgement needs a verdict for true and one for false");
            }
        }
    }

    /** Classifies into one of the named verdicts, then follows the matching one. */
    record NonBinaryJudgementNode(String criteria, List<VerdictNode> children) implements DagNode {

        public NonBinaryJudgementNode {
            require(criteria, "judgement criteria");
            children = List.copyOf(children);
            if (children.size() < 2) {
                throw new IllegalArgumentException(
                    "a non-binary judgement needs at least two verdicts");
            }
        }

        /** The labels the classifier may return, in the order they were declared. */
        public List<String> options() {
            return children.stream().map(child -> String.valueOf(child.verdict())).toList();
        }
    }

    /**
     * Where a branch ends, or where it continues.
     *
     * <p>A verdict carries a score or a child, never both and never neither. A verdict
     * with both would let a path score twice, and a verdict with neither is a path that
     * reaches no outcome.
     *
     * @param verdict {@code Boolean} under a binary judgement, {@code String} under a
     *                non-binary one
     * @param score   0 to 10, normalised by {@link Dag} to the 0 to 1 range a metric reports
     */
    record VerdictNode(Object verdict, OptionalInt score, Optional<DagNode> child)
        implements DagNode {

        public VerdictNode {
            if (verdict == null) throw new IllegalArgumentException("verdict required");
            if (score.isPresent() == child.isPresent()) {
                throw new IllegalArgumentException(
                    "verdict " + verdict + " needs a score or a child, and not both");
            }
            if (score.isPresent() && (score.getAsInt() < 0 || score.getAsInt() > 10)) {
                throw new IllegalArgumentException("verdict score runs from 0 to 10");
            }
        }

        public static VerdictNode scoring(Object verdict, int score) {
            return new VerdictNode(verdict, OptionalInt.of(score), Optional.empty());
        }

        public static VerdictNode leadingTo(Object verdict, DagNode child) {
            return new VerdictNode(verdict, OptionalInt.empty(), Optional.of(child));
        }
    }

    private static void require(String value, String what) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(what + " required");
    }
}
