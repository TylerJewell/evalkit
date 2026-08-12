package io.akka.evalkit.application;

import io.akka.evalkit.domain.CampaignPlan;
import io.akka.evalkit.domain.Lanes;
import io.akka.evalkit.domain.Precursor;
import io.akka.evalkit.domain.Observation;
import io.akka.evalkit.domain.RunOutcome;
import io.akka.evalkit.domain.Rubric;
import io.akka.evalkit.domain.Scenario;
import io.akka.evalkit.domain.Scorer;
import io.akka.evalkit.domain.ScorerRouter;
import io.akka.evalkit.domain.SystemUnderTest;
import io.akka.evalkit.ledger.Interactions;
import io.akka.evalkit.domain.Grade;
import io.akka.evalkit.metric.Finding;
import io.akka.evalkit.metric.Metric;
import io.akka.evalkit.metric.MetricRef;
import io.akka.evalkit.metric.ToolPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A campaign whose scenarios are settled by three different families.
 *
 * <p>Routing decides where a scenario goes before any call is made, so this asserts what
 * ran as much as what was reported. A campaign that quietly judged every scenario would
 * produce the same pass count and cost a model call per row.
 */
@DisplayName("CampaignRunner · routing across comparison, computation and a judge")
class RoutedCampaignTest {

    private static final Rubric RUBRIC = new Rubric("scenario-judge", 2,
        "{replay_history}{simulation_history}{system_output}{expected_outcome}");

    private static final MetricRef LENGTH = new MetricRef("answer-length", 1);
    private static final MetricRef TOOLS = new MetricRef("tool-permission", 1);

    private static final class Target implements SystemUnderTest {
        @Override
        public Prepared prepare(Precursor precursor) {
            return new Prepared.Ready("s", "");
        }

        @Override
        public Reply submit(String sessionId, String userText) {
            return Reply.from("the refund takes 30 days", "GenUC-17a")
                .taking(java.time.Duration.ofMillis(240))
                .calling(Interactions.tool("search_kb"), Interactions.tool("delete_account"));
        }

        @Override
        public Map<String, String> fixtures() {
            return Map.of("ready", "a prepared state");
        }
    }

    /** Counts the calls it received, so a test can prove a family was skipped. */
    private static final class CountingJudge implements Scorer {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public RunOutcome score(Observation observation) {
            calls.incrementAndGet();
            return new RunOutcome.Scored(Grade.of(observation.scenarioName(), RUBRIC, 9, ""));
        }
    }

    /** Scores on reply length, which needs no model and no key. */
    private static final class AnswerLength implements Metric, Scorer {
        final AtomicInteger calls = new AtomicInteger();

        @Override public MetricRef ref() { return LENGTH; }
        @Override public double threshold() { return 0.5; }

        @Override
        public double aggregate(List<Finding> findings) {
            return Metric.shareAffirmed(findings);
        }

        @Override
        public RunOutcome score(Observation observation) {
            calls.incrementAndGet();
            boolean brief = observation.transcript().systemOutput().length() <= 40;
            return outcome(List.of(brief
                ? Finding.affirmed("reply length")
                : Finding.denied("reply length", "over 40 characters")));
        }
    }

    private static Scenario asserted(String id, String node) {
        return new Scenario(id, Optional.of(node), Optional.empty(),
            Precursor.Fixture.named("ready"), "how do I claim?", "reaches " + node);
    }

    private static Scenario measured(String id) {
        return new Scenario(id, Optional.empty(), Optional.of(LENGTH),
            Precursor.Fixture.named("ready"), "how do I claim?", "a short reply");
    }

    private static Scenario judged(String id) {
        return new Scenario(id, Optional.empty(), Optional.empty(),
            Precursor.Fixture.named("ready"), "how do I claim?", "the agent explains how");
    }

    @Test
    @DisplayName("each family settles its own scenarios and the report keeps them apart")
    void threeFamiliesInOneCampaign() {
        var judge = new CountingJudge();
        var length = new AnswerLength();
        var router = ScorerRouter.byExpectation(judge, Map.of(LENGTH, length));

        var plan = new CampaignPlan("mixed",
            List.of(asserted("a1", "GenUC-17a"), asserted("a2", "GenUC-99z"),
                    measured("m1"), judged("j1")),
            Lanes.of(2), RUBRIC);

        var result = CampaignRunner.run(plan, new Target(), router);
        var report = result.report();

        assertThat(report.total()).isEqualTo(4);
        assertThat(report.asserted()).isEqualTo(2);
        assertThat(report.measured()).isEqualTo(1);
        assertThat(report.scored()).isEqualTo(1);

        // a1 reaches the node it names, a2 does not, m1 is over 40 characters, j1 scores 9.
        assertThat(report.assertedPassed()).isEqualTo(1);
        assertThat(report.measuredPassed()).isEqualTo(1);
        assertThat(report.passed()).isEqualTo(3);
    }

    @Test
    @DisplayName("only the scenarios that need a judge reach one")
    void theJudgeSeesOnlyWhatNeedsIt() {
        var judge = new CountingJudge();
        var length = new AnswerLength();
        var router = ScorerRouter.byExpectation(judge, Map.of(LENGTH, length));

        var plan = new CampaignPlan("mixed",
            List.of(asserted("a1", "GenUC-17a"), asserted("a2", "GenUC-17a"),
                    measured("m1"), judged("j1")),
            Lanes.of(2), RUBRIC);

        CampaignRunner.run(plan, new Target(), router);

        // Three of the four scenarios cost nothing. Routing is the whole reason a dataset
        // of several hundred is affordable.
        assertThat(judge.calls.get()).isEqualTo(1);
        assertThat(length.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a scenario naming a metric this campaign did not register is inconclusive")
    void unregisteredMetricProducesNoEvidence() {
        var router = ScorerRouter.byExpectation(new CountingJudge(), Map.of());

        var plan = new CampaignPlan("mixed", List.of(measured("m1")), Lanes.of(1), RUBRIC);

        var result = CampaignRunner.run(plan, new Target(), router);

        // A scorer that cannot run produced no evidence. Reporting a zero would blame the
        // service for a campaign that was misconfigured.
        assertThat(result.report().inconclusive()).isEqualTo(1);
        assertThat(result.report().measured()).isZero();
        assertThat(result.outcomes().get(0).describe()).contains("answer-length v1");
    }

    @Test
    @DisplayName("a metric that throws is inconclusive, on the same terms as a refused judge")
    void aThrowingMetricProducesNoEvidence() {
        Scorer throwing = transcript -> {
            throw new IllegalStateException("embedding service unreachable");
        };
        var router = ScorerRouter.byExpectation(new CountingJudge(), Map.of(LENGTH, throwing));

        var plan = new CampaignPlan("mixed", List.of(measured("m1")), Lanes.of(1), RUBRIC);

        var result = CampaignRunner.run(plan, new Target(), router);

        // Unreachable infrastructure is this kit failing, not the metric declining.
        assertThat(result.report().scorerFailed()).isEqualTo(1);
        assertThat(result.report().inconclusive()).isZero();
        assertThat(result.outcomes().get(0).describe()).contains("embedding service unreachable");
    }

    @Test
    @DisplayName("a scenario naming both a node and a metric is refused at construction")
    void aScenarioSettlesOneWay() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new Scenario("x", Optional.of("GenUC-1"), Optional.of(LENGTH),
                    new Precursor.None(), "hi", "something"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("more than one expectation: a specification node and a metric");
    }

    @Test
    @DisplayName("a campaign with no metrics routes everything a node does not settle to the judge")
    void judgingEverythingKeepsTheOldBehaviour() {
        var judge = new CountingJudge();
        var router = ScorerRouter.judgingEverything(judge);

        var plan = new CampaignPlan("mixed",
            List.of(asserted("a1", "GenUC-17a"), judged("j1"), judged("j2")),
            Lanes.of(2), RUBRIC);

        var result = CampaignRunner.run(plan, new Target(), router);

        assertThat(judge.calls.get()).isEqualTo(2);
        assertThat(result.report().asserted()).isEqualTo(1);
        assertThat(result.report().measured()).isZero();
    }

    /** Runs the ported ToolPermission metric over what the target actually called. */
    private static final class ToolPolicy implements io.akka.evalkit.domain.Scorer {
        private final ToolPermission metric = ToolPermission.allowing("search_kb", "reply");

        @Override
        public RunOutcome score(Observation observation) {
            // The tool names come from the evidence the run recorded, which is the whole
            // reason a observation carries more than the four rubric fields.
            return metric.outcome(metric.judge(observation.toolNames()));
        }
    }

    @Test
    @DisplayName("a ported metric scores the tools the target actually called")
    void aPortedMetricRunsInACampaign() {
        var router = ScorerRouter.byExpectation(new CountingJudge(), Map.of(TOOLS, new ToolPolicy()));
        var scenario = new Scenario("t1", Optional.empty(), Optional.of(TOOLS),
            Precursor.Fixture.named("ready"), "how do I claim?", "calls only allowed tools");

        var result = CampaignRunner.run(
            new CampaignPlan("tools", List.of(scenario), Lanes.of(1), RUBRIC),
            new Target(), router);

        // The target calls search_kb and delete_account. One of two is authorised.
        var outcome = (RunOutcome.Measured) result.outcomes().get(0);
        assertThat(outcome.metricId()).isEqualTo("tool-permission");
        assertThat(outcome.value()).isEqualTo(0.5);
        assertThat(outcome.withinThreshold()).isFalse();
        assertThat(result.report().measured()).isEqualTo(1);
        assertThat(result.report().measuredFailed()).isEqualTo(1);
    }

    @Test
    @DisplayName("latency and tool calls reach a scorer, and a judge never sees them")
    void evidenceTravelsBesideTheTranscript() {
        var seen = new java.util.concurrent.atomic.AtomicReference<Observation>();
        io.akka.evalkit.domain.Scorer capture = observation -> {
            seen.set(observation);
            return new RunOutcome.Scored(Grade.of(observation.scenarioName(), RUBRIC, 9, ""));
        };

        CampaignRunner.run(
            new CampaignPlan("evidence", List.of(judged("j1")), Lanes.of(1), RUBRIC),
            new Target(), ScorerRouter.judgingEverything(capture));

        assertThat(seen.get().latency()).contains(java.time.Duration.ofMillis(240));
        assertThat(seen.get().toolNames())
            .containsExactly("search_kb", "delete_account");
        // A rubric interpolates four fields and none of them is a tool call, so the
        // judge's input is unchanged by anything added to the evidence.
        assertThat(seen.get().transcript().systemOutput()).isEqualTo("the refund takes 30 days");
    }
}
