package io.akka.evalkit.domain;

import io.akka.evalkit.application.CampaignRunner;
import io.akka.evalkit.metric.Judgement;
import io.akka.evalkit.metric.MetricRef;
import io.akka.evalkit.metric.ToolPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An outcome variant nobody produces is a variant nobody has checked.
 *
 * <p>The sealed interface guarantees every switch handles a new variant. It guarantees
 * nothing about whether a campaign can reach one, whether the report counts it, or whether
 * the summary prints it. This walks the permitted subclasses and fails when the corpus
 * below leaves one unreached.
 */
@DisplayName("RunOutcome · every variant is reachable, counted and printed")
class OutcomeCoverageTest {

    private static final Rubric RUBRIC = new Rubric("scenario-judge", 2,
        "{replay_history}{simulation_history}{system_output}{expected_outcome}");

    private static final MetricRef TOOLS = new MetricRef("tool-permission", 1);

    /** Answers, refuses to prepare, or falls silent, depending on the scenario id. */
    private static final class Target implements SystemUnderTest {
        @Override
        public Prepared prepare(Precursor precursor) {
            return precursor instanceof Precursor.Fixture fixture
                && fixture.name().equals("broken")
                ? new Prepared.Failed("the fixture endpoint returned 500")
                : new Prepared.Ready("s", "");
        }

        @Override
        public Reply submit(String sessionId, String userText) {
            if (userText.equals("silence")) return Reply.of("");
            return Reply.from("the refund takes 30 days", "GenUC-17a")
                .calling(ToolCall.named("search_kb"), ToolCall.named("delete_account"));
        }

        @Override
        public Map<String, String> fixtures() {
            return Map.of("ready", "a prepared state", "broken", "a state that cannot be built");
        }
    }

    private static Scenario scenario(String id, String fixture, String say, Optional<String> node,
                                     Optional<MetricRef> metric) {
        return new Scenario(id, node, metric, Precursor.Fixture.named(fixture), say, "something");
    }

    /** One campaign reaching every variant the runner can produce. */
    private static CampaignRunner.Result runEveryVariant() {
        var toolPolicy = ToolPermission.allowing("search_kb");
        Scorer tools = recording ->
            toolPolicy.outcome(toolPolicy.judge(recording.evidence().toolNames()));
        // A scorer that ran and declined, which is a fact about the transcript.
        Scorer declining = recording ->
            new RunOutcome.Unscoreable("the content filter would not score " + recording.scenarioName());
        // A scorer that broke, which is a fact about this kit. The two are different rows.
        Scorer throwing = recording -> {
            throw new IllegalStateException("the scorer threw on " + recording.scenarioName());
        };

        var scenarios = List.of(
            scenario("scored", "ready", "how?", Optional.empty(), Optional.empty()),
            scenario("asserted", "ready", "how?", Optional.of("GenUC-17a"), Optional.empty()),
            scenario("measured", "ready", "how?", Optional.empty(), Optional.of(TOOLS)),
            scenario("setup-failed", "broken", "how?", Optional.empty(), Optional.empty()),
            scenario("no-reply", "ready", "silence", Optional.empty(), Optional.empty()),
            scenario("unscoreable", "ready", "decline", Optional.empty(), Optional.empty()),
            scenario("scorer-failed", "ready", "throw", Optional.empty(), Optional.empty()));

        ScorerRouter router = candidate -> {
            if (candidate.specNode().isPresent()) return Optional.empty();
            if (candidate.metric().isPresent()) return Optional.of(tools);
            if (candidate.id().equals("unscoreable")) return Optional.of(declining);
            if (candidate.id().equals("scorer-failed")) return Optional.of(throwing);
            return Optional.of(recording ->
                new RunOutcome.Scored(Verdict.of(recording.scenarioName(), RUBRIC, 9, "")));
        };

        return CampaignRunner.run(
            new CampaignPlan("coverage", scenarios, Lanes.of(2), RUBRIC), new Target(), router);
    }

    private static Set<String> variantsIn(List<RunOutcome> outcomes) {
        return outcomes.stream()
            .map(outcome -> outcome.getClass().getSimpleName())
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> declaredVariants() {
        return java.util.Arrays.stream(RunOutcome.class.getPermittedSubclasses())
            .map(Class::getSimpleName)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("the sealed interface declares the variants this test knows about")
    void theDeclaredVariantsAreTheExpectedSet() {
        // Fails when a variant is added, which is the reminder to give it a case below.
        assertThat(declaredVariants())
            .containsExactly("Asserted", "Measured", "NotReached", "Scored", "ScorerFailed",
                "Unscoreable");
    }

    @Test
    @DisplayName("one campaign reaches every declared variant")
    void everyVariantIsReachable() {
        var produced = variantsIn(runEveryVariant().outcomes());

        assertThat(produced)
            .as("variants no campaign in this suite produces")
            .containsExactlyElementsOf(declaredVariants());
    }

    @Test
    @DisplayName("both causes of a run producing nothing are reachable")
    void bothCausesAreReachable() {
        var causes = runEveryVariant().outcomes().stream()
            .filter(RunOutcome.NotReached.class::isInstance)
            .map(outcome -> ((RunOutcome.NotReached) outcome).cause())
            .collect(Collectors.toCollection(TreeSet::new));

        // A setup that never landed and a system that fell silent are different findings
        // for a reader, and the report splits them for that reason.
        assertThat(causes).containsExactlyInAnyOrder(RunOutcome.Cause.SETUP_FAILED,
            RunOutcome.Cause.NO_REPLY);
    }

    @Test
    @DisplayName("the report counts every variant it was given")
    void everyVariantIsCounted() {
        var report = runEveryVariant().report();

        assertThat(report.total()).isEqualTo(7);
        assertThat(report.asserted()).isEqualTo(1);
        assertThat(report.measured()).isEqualTo(1);
        assertThat(report.scored()).isEqualTo(1);
        assertThat(report.notReached()).isEqualTo(2);
        assertThat(report.unscoreable()).isEqualTo(1);
        assertThat(report.scorerFailed()).isEqualTo(1);
        assertThat(report.setupFailed()).isEqualTo(1);
        assertThat(report.noReply()).isEqualTo(1);
    }

    @Test
    @DisplayName("every variant describes itself in words a report row can print")
    void everyVariantDescribesItself() {
        for (RunOutcome outcome : runEveryVariant().outcomes()) {
            assertThat(outcome.describe())
                .as("description of " + outcome.getClass().getSimpleName())
                .isNotBlank()
                // A row reading "Measured[metricId=...]" is a record dumped into a report.
                .doesNotContain("[")
                .doesNotContain("@");
        }
    }

    @Test
    @DisplayName("the counts of every variant add up to the total")
    void theCountsAddUp() {
        var report = runEveryVariant().report();

        assertThat(report.judged() + report.withoutEvidence()).isEqualTo(report.total());
        assertThat(report.notReached() + report.unscoreable() + report.scorerFailed())
            .isEqualTo(report.withoutEvidence());
        assertThat(report.passed() + report.review() + report.failed())
            .isEqualTo(report.judged());
        assertThat(report.assertedPassed() + report.assertedFailed())
            .isEqualTo(report.asserted());
        assertThat(report.measuredPassed() + report.measuredFailed())
            .isEqualTo(report.measured());
        assertThat(report.scoredPassed() + report.scoredFailed())
            .isEqualTo(report.scored());
    }

    @Test
    @DisplayName("only the variants that assessed the system count as evidence")
    void evidenceIsTheThreeThatAssessed() {
        var byEvidence = runEveryVariant().outcomes().stream()
            .collect(Collectors.partitioningBy(RunOutcome::isEvidence,
                Collectors.mapping(o -> o.getClass().getSimpleName(),
                    Collectors.toCollection(TreeSet::new))));

        assertThat(byEvidence.get(true)).containsExactly("Asserted", "Measured", "Scored");
        assertThat(byEvidence.get(false))
            .containsExactly("NotReached", "ScorerFailed", "Unscoreable");
    }

    @Test
    @DisplayName("a judgement built by hand agrees with one a campaign produced")
    void metricOutcomeMatchesTheCampaign() {
        var metric = ToolPermission.allowing("search_kb");
        var direct = (RunOutcome.Measured) metric.outcome(
            metric.judge(List.of("search_kb", "delete_account")));

        var fromCampaign = runEveryVariant().outcomes().stream()
            .filter(RunOutcome.Measured.class::isInstance)
            .map(RunOutcome.Measured.class::cast)
            .findFirst()
            .orElseThrow();

        assertThat(fromCampaign.value()).isEqualTo(direct.value());
        assertThat(fromCampaign.metricId()).isEqualTo(direct.metricId());
    }

    @Test
    @DisplayName("a judgement list and its reversal score the same")
    void aggregationIgnoresOrder() {
        var metric = ToolPermission.allowing("a", "b");
        var forwards = List.of(Judgement.affirmed("a"), Judgement.denied("c", "no"));
        var backwards = List.of(Judgement.denied("c", "no"), Judgement.affirmed("a"));

        assertThat(metric.aggregate(forwards)).isEqualTo(metric.aggregate(backwards));
    }
}
