package io.akka.evalkit.conformance;

import io.akka.evalkit.domain.RunOutcome;
import io.akka.evalkit.metric.ArgumentCorrectness;
import io.akka.evalkit.metric.Judgement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What DeepEval's {@code ArgumentCorrectnessMetric} computes from a set of verdicts.
 *
 * <p><b>These scores are not copied from an upstream test, because there is none.</b> At
 * commit bd10fa6 the repository carries no {@code test_argument_correctness_metric.py}, so
 * the cases below are read off {@code _calculate_score} in
 * {@code deepeval/metrics/argument_correctness/argument_correctness.py}. {@code PortedMetrics}
 * records that this is a weaker claim than the pinned ports make.
 *
 * <p>Collecting the judgements needs a model upstream and here. The arithmetic does not, and
 * it is the arithmetic that these cases fix.
 */
@DisplayName("ArgumentCorrectness · matches DeepEval's ArgumentCorrectnessMetric")
class ArgumentCorrectnessConformanceTest {

    private static final ArgumentCorrectness METRIC = new ArgumentCorrectness();

    private static List<Judgement> judgements(boolean... correct) {
        var out = new java.util.ArrayList<Judgement>();
        for (int i = 0; i < correct.length; i++) {
            String call = "tool_call_" + (i + 1);
            out.add(correct[i] ? Judgement.affirmed(call)
                : Judgement.denied(call, "the arguments do not serve what was asked"));
        }
        return out;
    }

    @Nested
    @DisplayName("scores read off the upstream implementation")
    class UpstreamScores {

        @Test
        @DisplayName("every call made with the right arguments scores 1.0")
        void allCallsCorrect() {
            assertThat(METRIC.aggregate(judgements(true, true))).isEqualTo(1.0);
        }

        @Test
        @DisplayName("two of three correct scores 0.67")
        void someCallsCorrect() {
            double score = METRIC.aggregate(judgements(true, true, false));

            assertThat(score).isEqualTo(2.0 / 3);
            assertThat(METRIC.withinThreshold(score)).isTrue();
        }

        @Test
        @DisplayName("no call made correctly scores 0.0")
        void noCallsCorrect() {
            assertThat(METRIC.aggregate(judgements(false, false))).isEqualTo(0.0);
            assertThat(METRIC.withinThreshold(0.0)).isFalse();
        }

        @Test
        @DisplayName("upstream's empty-verdict arithmetic returns 1")
        void emptyVerdictsScoreOne() {
            // number_of_verdicts == 0 returns 1 upstream. The formula is kept; what this
            // kit reports for that run is the divergence below.
            assertThat(METRIC.aggregate(List.of())).isEqualTo(1.0);
        }

        @Test
        @DisplayName("the default threshold is upstream's 0.5")
        void defaultThreshold() {
            assertThat(METRIC.threshold()).isEqualTo(0.5);
        }
    }

    @Nested
    @DisplayName("where this port diverges, and why")
    class Divergence {

        @Test
        @DisplayName("a run with no tool call is unscoreable, not a full score")
        void noToolCallIsAbsentEvidence() {
            var outcome = METRIC.outcome(List.of());

            assertThat(outcome).isInstanceOf(RunOutcome.Unscoreable.class);
            assertThat(outcome.isEvidence()).isFalse();
            assertThat(outcome.describe()).contains("no tool call");
        }

        @Test
        @DisplayName("a run with tool calls is measured as usual")
        void aJudgedRunIsMeasured() {
            var outcome = METRIC.outcome(judgements(true, false));

            assertThat(outcome).isInstanceOf(RunOutcome.Measured.class);
            assertThat(((RunOutcome.Measured) outcome).value()).isEqualTo(0.5);
            assertThat(outcome.isEvidence()).isTrue();
        }
    }
}
