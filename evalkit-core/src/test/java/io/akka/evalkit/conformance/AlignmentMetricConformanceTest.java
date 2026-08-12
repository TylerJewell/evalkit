package io.akka.evalkit.conformance;

import io.akka.evalkit.domain.Observation;
import io.akka.evalkit.domain.RunOutcome;
import akka.javasdk.ledger.ModelResponse;
import akka.javasdk.ledger.ToolCall;
import io.akka.evalkit.ledger.Interactions;
import io.akka.evalkit.domain.Transcript;
import io.akka.evalkit.metric.AlignmentMetric;
import io.akka.evalkit.metric.PlanAdherence;
import io.akka.evalkit.metric.PlanQuality;
import io.akka.evalkit.metric.StepEfficiency;
import io.akka.evalkit.metric.TaskCompletion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four trace-level metrics, and what this kit does around the model call.
 *
 * <p><b>Nothing here reproduces an upstream value, because upstream publishes none.</b> At
 * commit bd10fa6 the tests for {@code PlanQualityMetric}, {@code PlanAdherenceMetric},
 * {@code StepEfficiencyMetric} and {@code TaskCompletionMetric} skip without an API key, run
 * a live agent, and assert only that the golden is not multimodal. {@code PortedMetrics}
 * records these as unpinned for that reason.
 *
 * <p>What is fixed below is the part that does not involve a model: the reply shape that is
 * read, the scale that is accepted, and what a run reports when there is nothing to ask
 * about. Those are this kit's behaviour and this kit's to keep.
 */
@DisplayName("Alignment metrics · a model's score, and what happens around it")
class AlignmentMetricConformanceTest {

    private static Transcript transcript() {
        return new Transcript("book-a-table", "", "user: book me a table",
            "Booked for 8pm.", "Books a table for the evening");
    }

    /**
     * A observation of a run that called these tools and nothing else.
     *
     * <p>An interaction record keeps a tool call under the model response that made it, so a
     * run with a tool call has a model call to hang it on and a run with neither records no
     * model responses at all.
     */
    private static Observation observation(ToolCall... called) {
        if (called.length == 0) {
            return new Observation(transcript(),
                Interactions.of("", "", "book me a table", List.of(),
                    Optional.empty(), Optional.empty()),
                Optional.empty());
        }
        return over(Interactions.calling(Interactions.response("Booked for 8pm."), called));
    }

    /** A observation whose model calls carried reasoning, which is where a plan lives. */
    private static Observation reasoning(String thinking, ToolCall... called) {
        return over(Interactions.thinking(
            Interactions.calling(Interactions.response("Booked for 8pm."), called), thinking));
    }

    private static Observation over(ModelResponse call) {
        return new Observation(transcript(),
            Interactions.of("", "", "book me a table", List.of(call),
                Optional.empty(), Optional.empty()),
            Optional.empty());
    }

    private static AlignmentMetric.Assessor answering(String reply) {
        return question -> reply;
    }

    @Nested
    @DisplayName("reading what the assessor said")
    class ReadingTheReply {

        @Test
        @DisplayName("a score and a reason become a measurement that carries both")
        void scoresAndCarriesTheReason() {
            var metric = new TaskCompletion(
                answering("SCORE: 0.8\nREASON: The table was booked for the right evening."));

            var outcome = metric.score(observation());

            assertThat(outcome).isInstanceOf(RunOutcome.Measured.class);
            var measured = (RunOutcome.Measured) outcome;
            assertThat(measured.metricId()).isEqualTo("task-completion");
            assertThat(measured.value()).isEqualTo(0.8);
            assertThat(measured.threshold()).isEqualTo(0.5);
            assertThat(measured.withinThreshold()).isTrue();
            assertThat(measured.explanation())
                .isEqualTo("The table was booked for the right evening.");
        }

        @Test
        @DisplayName("a score under the threshold is measured, not discarded")
        void aLowScoreIsStillEvidence() {
            var metric = new TaskCompletion(answering("SCORE: 0.2\nREASON: No table was booked."));

            var outcome = metric.score(observation());

            assertThat(outcome.passed()).isFalse();
            assertThat(outcome.isEvidence()).isTrue();
        }

        @Test
        @DisplayName("an assessor that returned no score produced no evidence")
        void anUnreadableReplyIsUnscoreable() {
            var metric = new TaskCompletion(answering("I would rather not assess this."));

            var outcome = metric.score(observation());

            assertThat(outcome).isInstanceOf(RunOutcome.Inconclusive.class);
            assertThat(outcome.isEvidence()).isFalse();
            assertThat(outcome.describe()).contains("no score");
        }

        @Test
        @DisplayName("a score on the wrong scale is refused rather than rescaled")
        void aScoreOutsideZeroToOneIsUnscoreable() {
            // 8 out of 10 is what a rubric judge returns. Dividing it by ten here would
            // invent a share the assessor never stated.
            var metric = new TaskCompletion(answering("SCORE: 8\nREASON: Mostly right."));

            var outcome = metric.score(observation());

            assertThat(outcome).isInstanceOf(RunOutcome.Inconclusive.class);
            assertThat(outcome.describe()).contains("not a share");
        }

        @Test
        @DisplayName("the bounds of the scale are read, not rejected")
        void zeroAndOneAreScores() {
            assertThat(new TaskCompletion(answering("SCORE: 0\nREASON: none of it"))
                .score(observation())).isInstanceOf(RunOutcome.Measured.class);
            assertThat(new TaskCompletion(answering("SCORE: 1\nREASON: all of it"))
                .score(observation())).isInstanceOf(RunOutcome.Measured.class);
        }

        @Test
        @DisplayName("a score with no reason is still a score")
        void aScoreWithoutAReason() {
            var outcome = new TaskCompletion(answering("SCORE: 0.6")).score(observation());

            assertThat(((RunOutcome.Measured) outcome).statesExplanation()).isFalse();
            assertThat(outcome.isEvidence()).isTrue();
        }
    }

    @Nested
    @DisplayName("what each metric puts to the assessor")
    class TheQuestion {

        @Test
        @DisplayName("task completion reads the stated task against what came back")
        void taskCompletionAsksAboutTheOutcome() {
            var asked = new AtomicReference<AlignmentMetric.Question>();
            new TaskCompletion(question -> {
                asked.set(question);
                return "SCORE: 1\nREASON: yes";
            }).score(observation());

            assertThat(asked.get().task()).isEqualTo("Books a table for the evening");
            assertThat(asked.get().against()).isEqualTo("Booked for 8pm.");
        }

        @Test
        @DisplayName("step efficiency reads the task against the tools that were called")
        void stepEfficiencyAsksAboutTheSteps() {
            var asked = new AtomicReference<AlignmentMetric.Question>();
            new StepEfficiency(question -> {
                asked.set(question);
                return "SCORE: 0.5\nREASON: two searches where one would do";
            }).score(observation(Interactions.tool("search"), Interactions.tool("book")));

            // The model call is a step. A recorded tool call hangs off the response that
            // made it, so a run that called a tool made a model call to call it from.
            assertThat(asked.get().against()).isEqualTo("model call\ntool: search\ntool: book");
        }

        @Test
        @DisplayName("plan adherence reads the plan and the task against the steps")
        void planAdherenceAsksAboutBoth() {
            var asked = new AtomicReference<AlignmentMetric.Question>();
            new PlanAdherence(question -> {
                asked.set(question);
                return "SCORE: 1\nREASON: followed";
            })
                .readingPlanFrom(observation -> Optional.of("search, then book"))
                .score(observation(Interactions.tool("search")));

            assertThat(asked.get().task()).contains("Books a table").contains("search, then book");
            assertThat(asked.get().against()).isEqualTo("model call\ntool: search");
        }
    }

    @Nested
    @DisplayName("the plan comes from the reasoning the run recorded")
    class RecordedReasoning {

        @Test
        @DisplayName("a run whose model calls carried reasoning is scored on it")
        void reasoningIsThePlan() {
            var asked = new AtomicReference<AlignmentMetric.Question>();
            var outcome = new PlanQuality(question -> {
                asked.set(question);
                return "SCORE: 0.9\nREASON: a workable plan";
            }).score(reasoning("Find a free table, then book it."));

            assertThat(outcome).isInstanceOf(RunOutcome.Measured.class);
            assertThat(asked.get().against()).isEqualTo("Find a free table, then book it.");
        }

        @Test
        @DisplayName("the same run without reasoning produces no score")
        void theSameRunWithoutReasoning() {
            // The pair that makes the case above worth having. Without it, a metric that
            // always returned Measured would pass the test above just as well.
            var outcome = new PlanQuality(answering("SCORE: 0.9\nREASON: unreachable"))
                .score(observation(Interactions.tool("search")));

            assertThat(outcome).isInstanceOf(RunOutcome.Inconclusive.class);
        }

        @Test
        @DisplayName("plan adherence reads the recorded reasoning too")
        void adherenceReadsReasoning() {
            var outcome = new PlanAdherence(answering("SCORE: 1\nREASON: followed"))
                .score(reasoning("Search, then book.", Interactions.tool("search")));

            assertThat(outcome).isInstanceOf(RunOutcome.Measured.class);
        }

        @Test
        @DisplayName("an explicit plan source overrides the recorded reasoning")
        void anExplicitSourceWins() {
            var asked = new AtomicReference<AlignmentMetric.Question>();
            new PlanQuality(question -> {
                asked.set(question);
                return "SCORE: 1\nREASON: fine";
            })
                .readingPlanFrom(r -> Optional.of("the plan the target reported"))
                .score(reasoning("the reasoning the provider returned"));

            assertThat(asked.get().against()).isEqualTo("the plan the target reported");
        }

        @Test
        @DisplayName("a model call is a step, so a run that called no tool is still scored")
        void aModelCallIsAStep() {
            var asked = new AtomicReference<AlignmentMetric.Question>();
            var outcome = new StepEfficiency(question -> {
                asked.set(question);
                return "SCORE: 1\nREASON: went straight to it";
            }).score(reasoning("Just answer it."));

            assertThat(outcome).isInstanceOf(RunOutcome.Measured.class);
            assertThat(asked.get().against()).isEqualTo("model call, reasoning: Just answer it.");
        }
    }

    @Nested
    @DisplayName("where this port diverges, and why")
    class Divergence {

        @Test
        @DisplayName("a run with no plan is inconclusive, where upstream scores it 1 and passes")
        void noPlanIsAbsentEvidence() {
            var metric = new PlanQuality(answering("SCORE: 1\nREASON: unreachable"));

            var outcome = metric.score(observation(Interactions.tool("search")));

            assertThat(outcome).isInstanceOf(RunOutcome.Inconclusive.class);
            assertThat(outcome.isEvidence()).isFalse();
            assertThat(outcome.describe()).contains("no plan");
        }

        @Test
        @DisplayName("a target that reports its plan is scored on it")
        void aReportedPlanIsScored() {
            var metric = new PlanQuality(answering("SCORE: 0.9\nREASON: a workable plan"))
                .readingPlanFrom(observation -> Optional.of("find a table, then book it"));

            assertThat(metric.score(observation())).isInstanceOf(RunOutcome.Measured.class);
        }

        @Test
        @DisplayName("a blank plan is no plan")
        void aBlankPlanIsAbsent() {
            var metric = new PlanQuality(answering("SCORE: 1\nREASON: unreachable"))
                .readingPlanFrom(observation -> Optional.of("   "));

            assertThat(metric.score(observation())).isInstanceOf(RunOutcome.Inconclusive.class);
        }

        @Test
        @DisplayName("a run with no steps has no efficiency to judge")
        void noStepsIsAbsentEvidence() {
            var outcome = new StepEfficiency(answering("SCORE: 1\nREASON: unreachable"))
                .score(observation());

            assertThat(outcome).isInstanceOf(RunOutcome.Inconclusive.class);
            assertThat(outcome.describe()).contains("no steps");
        }

        @Test
        @DisplayName("plan adherence needs both halves before it asks anything")
        void adherenceNeedsAPlanAndSteps() {
            var withoutSteps = new PlanAdherence(answering("SCORE: 1\nREASON: unreachable"))
                .readingPlanFrom(observation -> Optional.of("search, then book"))
                .score(observation());
            var withoutPlan = new PlanAdherence(answering("SCORE: 1\nREASON: unreachable"))
                .score(observation(Interactions.tool("search")));

            assertThat(withoutSteps).isInstanceOf(RunOutcome.Inconclusive.class);
            assertThat(withoutPlan).isInstanceOf(RunOutcome.Inconclusive.class);
        }

        @Test
        @DisplayName("an inconclusive run costs no model call")
        void absenceIsDecidedBeforeTheCall() {
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            new PlanQuality(question -> {
                calls.incrementAndGet();
                return "SCORE: 1";
            }).score(observation());

            assertThat(calls).hasValue(0);
        }
    }

    @Nested
    @DisplayName("properties this port states for itself")
    class PortSpecific {

        @Test
        @DisplayName("each metric carries upstream's 0.5 threshold and its own id")
        void thresholdsAndIds() {
            var assessor = answering("SCORE: 1");

            assertThat(new TaskCompletion(assessor).ref().metricId()).isEqualTo("task-completion");
            assertThat(new StepEfficiency(assessor).ref().metricId()).isEqualTo("step-efficiency");
            assertThat(new PlanQuality(assessor).ref().metricId()).isEqualTo("plan-quality");
            assertThat(new PlanAdherence(assessor).ref().metricId()).isEqualTo("plan-adherence");

            assertThat(new TaskCompletion(assessor).threshold()).isEqualTo(0.5);
            assertThat(new StepEfficiency(assessor).threshold()).isEqualTo(0.5);
            assertThat(new PlanQuality(assessor).threshold()).isEqualTo(0.5);
            assertThat(new PlanAdherence(assessor).threshold()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("changing a threshold changes the metric version")
        void thresholdChangeIsAVersionChange() {
            var metric = new TaskCompletion(answering("SCORE: 1"));

            assertThat(metric.scoringAtLeast(0.8).ref().version())
                .isGreaterThan(metric.ref().version());
        }

        @Test
        @DisplayName("a measurement names the metric and version that produced it")
        void theRowNamesItsMetric() {
            var outcome = new TaskCompletion(answering("SCORE: 0.4\nREASON: half done"))
                .score(observation());

            assertThat(outcome.describe())
                .isEqualTo("task-completion v1: 0.40 against 0.50 — half done");
        }
    }
}
