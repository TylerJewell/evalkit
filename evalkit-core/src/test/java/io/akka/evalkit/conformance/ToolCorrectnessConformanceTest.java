package io.akka.evalkit.conformance;

import io.akka.evalkit.domain.ToolCall;
import io.akka.evalkit.metric.ToolCorrectness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What DeepEval's {@code ToolCorrectnessMetric} computes, case by case.
 *
 * <p><b>These scores are not copied from an upstream test, because there is none.</b> At
 * commit bd10fa6 the repository carries no {@code test_tool_correctness_metric.py}, so each
 * case below is worked through {@code deepeval/metrics/tool_correctness/tool_correctness.py}
 * by hand and the expected value written down. That is a weaker claim than the pinned ports
 * make, and {@code PortedMetrics} records it as such.
 *
 * <p>The metric is deterministic, so these run with no provider and no key.
 */
@DisplayName("ToolCorrectness · matches DeepEval's ToolCorrectnessMetric")
class ToolCorrectnessConformanceTest {

    private static double score(ToolCorrectness metric, String... called) {
        return metric.aggregate(metric.judge(java.util.Arrays.stream(called)
            .map(ToolCall::named).toList()));
    }

    private static ToolCall call(String name, String key, String value) {
        return new ToolCall(name, Map.of(key, value));
    }

    @Nested
    @DisplayName("scores worked through the upstream implementation")
    class UpstreamScores {

        @Test
        @DisplayName("every expected tool called scores 1.0")
        void allExpectedToolsCalled() {
            var metric = ToolCorrectness.expecting("search_kb", "reply");

            assertThat(score(metric, "search_kb", "reply")).isEqualTo(1.0);
            assertThat(metric.withinThreshold(1.0)).isTrue();
        }

        @Test
        @DisplayName("one expected tool of two scores 0.5 and names the one missed")
        void oneExpectedToolMissed() {
            var metric = ToolCorrectness.expecting("search_kb", "reply");
            var judgements = metric.judge(List.of(ToolCall.named("search_kb")));

            assertThat(metric.aggregate(judgements)).isEqualTo(0.5);
            assertThat(ToolCorrectness.missing(judgements)).containsExactly("reply");
        }

        @Test
        @DisplayName("the denominator is what was expected, so extra calls do not lower it")
        void extraCallsDoNotCount() {
            // total_score / len(expected_tools) upstream. Whether the agent called more
            // than it needed is ToolPermission's question, not this one's.
            var metric = ToolCorrectness.expecting("search_kb");

            assertThat(score(metric, "search_kb", "log_event", "notify")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("expecting nothing and calling nothing scores 1.0")
        void nothingExpectedAndNothingCalled() {
            assertThat(score(ToolCorrectness.expecting())).isEqualTo(1.0);
        }

        @Test
        @DisplayName("expecting nothing and calling something scores 0.0")
        void nothingExpectedButSomethingCalled() {
            assertThat(score(ToolCorrectness.expecting(), "delete_account")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("one call satisfies one expectation, not two")
        void aCalledToolIsSpentOnce() {
            var metric = ToolCorrectness.expecting("search_kb", "search_kb");

            assertThat(score(metric, "search_kb")).isEqualTo(0.5);
        }

        @Test
        @DisplayName("exact match wants the same tools in the same positions")
        void exactMatch() {
            var metric = ToolCorrectness.expecting("a", "b").exactly();

            assertThat(score(metric, "a", "b")).isEqualTo(1.0);
            assertThat(score(metric, "b", "a")).isEqualTo(0.0);
            assertThat(score(metric, "a")).isEqualTo(0.0);
            assertThat(score(metric, "a", "b", "c")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("exact match on nothing expected and nothing called scores 1.0")
        void exactMatchOnEmpty() {
            assertThat(score(ToolCorrectness.expecting().exactly())).isEqualTo(1.0);
        }

        @Test
        @DisplayName("ordering credits the longest run that kept the sequence")
        void orderingCreditsTheLongestRun() {
            // expected [a, b, c] against called [a, c, b]: two of the three can be read in
            // order, whichever pair is taken, so the weighted length is 2 over 3 expected.
            var metric = ToolCorrectness.expecting("a", "b", "c").inOrder();

            assertThat(score(metric, "a", "c", "b")).isEqualTo(2.0 / 3);
            assertThat(score(metric, "a", "b", "c")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("ordering scores nothing when the sequence is reversed but for one")
        void orderingOnAReversedSequence() {
            var metric = ToolCorrectness.expecting("a", "b").inOrder();

            assertThat(score(metric, "b", "a")).isEqualTo(0.5);
        }

        @Test
        @DisplayName("matching arguments scores the share of keys that agree")
        void argumentsAgreeInPart() {
            var metric = ToolCorrectness.expecting(
                List.of(new ToolCall("search", Map.of("query", "refund", "limit", "5"))))
                .comparingArguments();

            var bothRight = metric.judge(List.of(
                new ToolCall("search", Map.of("query", "refund", "limit", "5"))));
            var oneRight = metric.judge(List.of(
                new ToolCall("search", Map.of("query", "refund", "limit", "50"))));
            var noneRight = metric.judge(List.of(
                new ToolCall("search", Map.of("query", "cancel", "limit", "50"))));

            assertThat(metric.aggregate(bothRight)).isEqualTo(1.0);
            assertThat(metric.aggregate(oneRight)).isEqualTo(0.5);
            assertThat(metric.aggregate(noneRight)).isEqualTo(0.0);
        }

        @Test
        @DisplayName("an argument key the call never sent counts against the share")
        void argumentsMissingAKey() {
            var metric = ToolCorrectness.expecting(
                List.of(new ToolCall("search", Map.of("query", "refund"))))
                .comparingArguments();

            // Keys are counted over the union, so an extra argument dilutes a right one.
            assertThat(metric.aggregate(metric.judge(List.of(
                new ToolCall("search", Map.of("query", "refund", "locale", "en"))))))
                .isEqualTo(0.5);
        }

        @Test
        @DisplayName("exact match compares arguments whole, with no partial credit")
        void exactMatchArgumentsAreAllOrNothing() {
            var metric = ToolCorrectness.expecting(
                List.of(new ToolCall("search", Map.of("query", "refund", "limit", "5"))))
                .exactly().comparingArguments();

            assertThat(metric.aggregate(metric.judge(List.of(
                new ToolCall("search", Map.of("query", "refund", "limit", "50"))))))
                .isEqualTo(0.0);
        }

        @Test
        @DisplayName("names are compared when arguments are not")
        void argumentsIgnoredByDefault() {
            var metric = ToolCorrectness.expecting(List.of(call("search", "query", "refund")));

            assertThat(metric.aggregate(metric.judge(List.of(call("search", "query", "cancel")))))
                .isEqualTo(1.0);
        }

        @Test
        @DisplayName("strict mode scores anything short of the threshold zero")
        void strictModeZeroesPartialSuccess() {
            var metric = ToolCorrectness.expecting("a", "b").strict();

            assertThat(score(metric, "a")).isEqualTo(0.0);
            assertThat(score(metric, "a", "b")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("the default threshold is upstream's 0.5")
        void defaultThreshold() {
            assertThat(ToolCorrectness.expecting("a").threshold()).isEqualTo(0.5);
        }
    }

    @Nested
    @DisplayName("comparing what the tool returned")
    class ComparingOutput {

        private static final List<ToolCall> EXPECTED =
            List.of(new ToolCall("", "search", Map.of("query", "tables"), "3 tables free"));

        private static double scoreAgainst(ToolCorrectness metric, String response) {
            return metric.aggregate(metric.judge(List.of(
                new ToolCall("", "search", Map.of("query", "tables"), response))));
        }

        @Test
        @DisplayName("a matching return scores the call in full")
        void returnsAgree() {
            var metric = ToolCorrectness.expecting(EXPECTED).comparingOutput();

            assertThat(scoreAgainst(metric, "3 tables free")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a different return scores the call zero")
        void returnsDiffer() {
            var metric = ToolCorrectness.expecting(EXPECTED).comparingOutput();

            assertThat(scoreAgainst(metric, "no tables free")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("a wrong return outranks right arguments")
        void outputBeatsArguments() {
            // Upstream zeroes the match on an output mismatch whatever the arguments scored,
            // so partial argument credit cannot rescue a call that returned the wrong thing.
            var metric = ToolCorrectness.expecting(EXPECTED).comparingArguments().comparingOutput();

            assertThat(scoreAgainst(metric, "no tables free")).isEqualTo(0.0);
            assertThat(scoreAgainst(metric, "3 tables free")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a target that recorded no return scores zero, which is the documented limit")
        void unrecordedReturn() {
            var metric = ToolCorrectness.expecting(EXPECTED).comparingOutput();

            assertThat(scoreAgainst(metric, "")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("the return is ignored unless the comparison was asked for")
        void ignoredByDefault() {
            var metric = ToolCorrectness.expecting(EXPECTED);

            assertThat(scoreAgainst(metric, "no tables free")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("exact match compares the return alongside the name and arguments")
        void exactMatchComparesTheReturn() {
            var metric = ToolCorrectness.expecting(EXPECTED).exactly().comparingOutput();

            assertThat(scoreAgainst(metric, "3 tables free")).isEqualTo(1.0);
            assertThat(scoreAgainst(metric, "no tables free")).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("properties this port states for itself")
    class PortSpecific {

        @Test
        @DisplayName("the score depends on the judgements alone")
        void aggregateIsPure() {
            var metric = ToolCorrectness.expecting("a", "b");
            var judgements = metric.judge(List.of(ToolCall.named("a")));

            assertThat(metric.aggregate(judgements)).isEqualTo(metric.aggregate(judgements));
            assertThat(metric.aggregate(judgements)).isEqualTo(0.5);
        }

        @Test
        @DisplayName("changing the threshold changes the metric version")
        void thresholdChangeIsAVersionChange() {
            var original = ToolCorrectness.expecting("a");

            assertThat(original.scoringAtLeast(0.8).ref().version())
                .isGreaterThan(original.ref().version());
        }

        @Test
        @DisplayName("a partly matching call is affirmed only when it matches whole")
        void partialCreditIsNotAnAffirmation() {
            var metric = ToolCorrectness.expecting(
                List.of(new ToolCall("search", Map.of("query", "refund", "limit", "5"))))
                .comparingArguments();

            var judgement = metric.judge(List.of(
                new ToolCall("search", Map.of("query", "refund", "limit", "50")))).get(0);

            assertThat(judgement.credit()).isEqualTo(0.5);
            assertThat(judgement.affirmed()).isFalse();
        }
    }
}
