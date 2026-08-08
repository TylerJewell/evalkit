package io.akka.evalkit.conformance;

import io.akka.evalkit.metric.dag.Dag;
import io.akka.evalkit.metric.dag.DagJudge;
import io.akka.evalkit.metric.dag.DagNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The graph, the scripted answers and the expected score are DeepEval's.
 *
 * <p>Source: {@code tests/test_metrics/test_dag.py} at commit bd10fa6. Upstream builds a
 * graph whose ordering node hangs under two parents, answers each node type once with a
 * deterministic model, and asserts a score of 1 together with one call per node type.
 */
@DisplayName("Dag · matches DeepEval's legacy DAG traversal")
class DagConformanceTest {

    /** Records which node types were asked, as the upstream model does. */
    private static final class ScriptedJudge implements DagJudge {

        final List<String> asked = new ArrayList<>();

        @Override
        public List<String> extract(DagNode.TaskNode node, Map<String, List<String>> evidence) {
            asked.add("task");
            return List.of("Intro", "Body", "Conclusion");
        }

        @Override
        public boolean decide(DagNode.BinaryJudgementNode node, Map<String, List<String>> evidence) {
            asked.add("binary");
            return true;
        }

        @Override
        public String classify(DagNode.NonBinaryJudgementNode node, Map<String, List<String>> evidence) {
            asked.add("choice");
            return "Yes";
        }
    }

    /** The shared-node graph from the upstream fixture. */
    private static Dag legacyDag() {
        var correctOrder = new DagNode.NonBinaryJudgementNode(
            "Are the summary headings in the correct order: 'intro' => 'body' => 'conclusion'?",
            List.of(DagNode.VerdictNode.scoring("Yes", 10),
                    DagNode.VerdictNode.scoring("Two are out of order", 4),
                    DagNode.VerdictNode.scoring("All out of order", 2)));

        var correctHeadings = new DagNode.BinaryJudgementNode(
            "Does the summary headings contain all three: 'intro', 'body', and 'conclusion'?",
            List.of(DagNode.VerdictNode.scoring(false, 0),
                    DagNode.VerdictNode.leadingTo(true, correctOrder)));

        // correctOrder hangs under both the task node and the binary judgement, which is
        // the shared node the upstream fixture exists to protect.
        var extractHeadings = new DagNode.TaskNode(
            "Extract all headings in `actual_output`",
            "Summary headings",
            List.of(correctHeadings, correctOrder));

        return Dag.rootedAt(extractHeadings);
    }

    @Test
    @DisplayName("the legacy graph scores 1.0 and asks each node type once")
    void legacyGraphRemainsExecutable() {
        var judge = new ScriptedJudge();

        var outcome = legacyDag().traverse(judge);

        assertThat(outcome.score()).isEqualTo(1.0);
        assertThat(outcome.reachedAScore()).isTrue();
        // Upstream asserts one call per schema. A shared node evaluated twice would
        // double the count and let one run answer the same question two ways.
        assertThat(java.util.Collections.frequency(judge.asked, "task")).isEqualTo(1);
        assertThat(java.util.Collections.frequency(judge.asked, "binary")).isEqualTo(1);
        assertThat(java.util.Collections.frequency(judge.asked, "choice")).isEqualTo(1);
    }

    @Test
    @DisplayName("a verdict score of 10 reports 1.0 and a score of 0 reports 0.0")
    void verdictScoresNormaliseToTheReportedRange() {
        var pass = DagNode.VerdictNode.scoring(true, 10);
        var fail = DagNode.VerdictNode.scoring(false, 0);
        var node = new DagNode.BinaryJudgementNode("is the output correct?", List.of(fail, pass));

        assertThat(Dag.rootedAt(node).traverse(alwaysDeciding(true)).score()).isEqualTo(1.0);
        assertThat(Dag.rootedAt(node).traverse(alwaysDeciding(false)).score()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("a middle verdict reports its own fraction")
    void middleVerdictScores() {
        var node = new DagNode.NonBinaryJudgementNode("how ordered?",
            List.of(DagNode.VerdictNode.scoring("Yes", 10),
                    DagNode.VerdictNode.scoring("Two are out of order", 4),
                    DagNode.VerdictNode.scoring("All out of order", 2)));

        assertThat(Dag.rootedAt(node).traverse(alwaysClassifying("Two are out of order")).score())
            .isEqualTo(0.4);
    }

    @Test
    @DisplayName("a label outside the registered verdicts stops the traversal and says so")
    void unregisteredLabelStopsTheTraversal() {
        var node = new DagNode.NonBinaryJudgementNode("how ordered?",
            List.of(DagNode.VerdictNode.scoring("Yes", 10),
                    DagNode.VerdictNode.scoring("No", 0)));

        var outcome = Dag.rootedAt(node).traverse(alwaysClassifying("Maybe"));

        // A graph cannot follow a branch it does not have. Reporting zero without the
        // reason would read as the service failing rather than the judge misbehaving.
        assertThat(outcome.reachedAScore()).isFalse();
        assertThat(outcome.stoppedBecause()).contains("Maybe").contains("[Yes, No]");
    }

    @Test
    @DisplayName("a verdict carrying both a score and a child is refused")
    void aVerdictCarriesAScoreOrAChild() {
        var child = new DagNode.BinaryJudgementNode("anything?",
            List.of(DagNode.VerdictNode.scoring(true, 10), DagNode.VerdictNode.scoring(false, 0)));

        assertThatThrownBy(() -> new DagNode.VerdictNode(
                true, java.util.OptionalInt.of(10), java.util.Optional.of(child)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("score or a child");
    }

    private static DagJudge alwaysDeciding(boolean verdict) {
        return new DagJudge() {
            @Override public List<String> extract(DagNode.TaskNode n, Map<String, List<String>> e) {
                return List.of();
            }
            @Override public boolean decide(DagNode.BinaryJudgementNode n, Map<String, List<String>> e) {
                return verdict;
            }
            @Override public String classify(DagNode.NonBinaryJudgementNode n, Map<String, List<String>> e) {
                throw new AssertionError("this graph asks no classification question");
            }
        };
    }

    private static DagJudge alwaysClassifying(String label) {
        return new DagJudge() {
            @Override public List<String> extract(DagNode.TaskNode n, Map<String, List<String>> e) {
                return List.of();
            }
            @Override public boolean decide(DagNode.BinaryJudgementNode n, Map<String, List<String>> e) {
                throw new AssertionError("this graph asks no binary question");
            }
            @Override public String classify(DagNode.NonBinaryJudgementNode n, Map<String, List<String>> e) {
                return label;
            }
        };
    }
}
