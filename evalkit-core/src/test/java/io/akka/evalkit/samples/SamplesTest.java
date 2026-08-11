package io.akka.evalkit.samples;

import io.akka.evalkit.domain.CampaignPlan;
import io.akka.evalkit.domain.Lanes;
import io.akka.evalkit.domain.Precursor;
import io.akka.evalkit.domain.Rubric;
import io.akka.evalkit.domain.Scenario;
import io.akka.evalkit.domain.SystemUnderTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("The sample domains")
class SamplesTest {

    private static final Rubric RUBRIC = Rubric.load("scenario-judge", 3);

    /** A target that can do everything the samples ask for, so only the dataset is checked. */
    private record Capable(Samples.Domain domain) implements SystemUnderTest {
        @Override
        public Prepared prepare(Precursor precursor) {
            return new Prepared.Ready("s1", "");
        }

        @Override
        public Reply submit(String sessionId, String userText) {
            return Reply.of("said something");
        }

        @Override
        public Map<String, String> fixtures() {
            return Samples.fixturesNamed();
        }

        @Override
        public Set<String> breakableTools() {
            return Set.of("lookup_order", "lookup_booking", "search_docs");
        }
    }

    @Test
    @DisplayName("every sample dataset passes the pre-flight it ships with")
    void everyDomainIsRunnable() {
        for (Samples.Domain domain : Samples.all()) {
            var plan = new CampaignPlan(domain.id(), domain.scenarios(), Lanes.of(2), RUBRIC)
                .under(domain.policy());

            assertThat(plan.check(new Capable(domain)))
                .as("%s refuses its own dataset", domain.id())
                .isInstanceOf(CampaignPlan.Check.Ready.class);
        }
    }

    @Test
    @DisplayName("every kind of check this kit settles has a scenario showing it")
    void everyKindOfCheckIsDemonstrated() {
        var scenarios = Samples.all().stream()
            .flatMap(d -> d.scenarios().stream()).toList();

        // A sample set that shows only one kind of check teaches only one kind of check.
        assertThat(scenarios).anySatisfy(s -> assertThat(s.specNode()).isPresent());
        assertThat(scenarios).anySatisfy(s ->
            assertThat(s.requiredPhrases()).isNotEmpty());
        assertThat(scenarios).anySatisfy(s -> assertThat(s.metric()).isPresent());
        assertThat(scenarios).anySatisfy(s -> assertThat(s.needsJudge()).isTrue());
    }

    @Test
    @DisplayName("the datasets show a broken tool and a walked path, not only seeded states")
    void theHardShapesAreShown() {
        var precursors = Samples.all().stream()
            .flatMap(d -> d.scenarios().stream())
            .map(Scenario::precursor).toList();

        assertThat(precursors).anySatisfy(p ->
            assertThat(p.brokenTools()).isNotEmpty());
        assertThat(precursors).anySatisfy(p ->
            assertThat(p.provesReachability()).isTrue());
        assertThat(precursors).anySatisfy(p ->
            assertThat(p).isInstanceOf(Precursor.Fixture.class));
    }

    @Test
    @DisplayName("every metric a sample names is one this kit ships")
    void metricsNamedAreMetricsThatExist() {
        var named = Samples.all().stream()
            .flatMap(d -> d.scenarios().stream())
            .flatMap(s -> s.metric().stream())
            .map(m -> m.metricId())
            .collect(Collectors.toSet());

        // A sample naming a metric nobody wrote is a sample that cannot run.
        assertThat(named).isSubsetOf(List.of("tool-permission", "tool-correctness",
            "argument-correctness", "task-completion", "step-efficiency", "plan-quality",
            "plan-adherence", "turn-relevancy", "turn-faithfulness", "citation-faithfulness"));
    }

    @Test
    @DisplayName("every fixture a sample names is one the sample set describes")
    void fixturesNamedAreDescribed() {
        var used = Samples.all().stream()
            .flatMap(d -> d.scenarios().stream())
            .map(Scenario::precursor)
            .flatMap(p -> fixtureNames(p).stream())
            .collect(Collectors.toSet());

        assertThat(Samples.fixturesNamed().keySet()).containsAll(used);
    }

    @Test
    @DisplayName("each domain loads its own versioned policy")
    void policiesLoad() {
        assertThat(Samples.refunds().policy().label()).isEqualTo("refund-desk v1");
        assertThat(Samples.bookings().policy().label()).isEqualTo("booking-desk v1");
        assertThat(Samples.helpdesk().policy().label()).isEqualTo("helpdesk v1");
        assertThat(Samples.refunds().policy().text()).contains("30 days");
    }

    @Test
    @DisplayName("a known issue names a scenario that exists")
    void knownIssuesNameRealScenarios() {
        // A list of known exceptions may only shrink, and an entry naming nothing is an
        // entry nobody can retire.
        for (Samples.Domain domain : Samples.all()) {
            var ids = domain.scenarios().stream().map(Scenario::id).toList();
            for (String issue : domain.knownIssues()) {
                assertThat(ids).anySatisfy(id -> assertThat(issue).contains(id));
            }
        }
    }

    private static List<String> fixtureNames(Precursor precursor) {
        if (precursor instanceof Precursor.Fixture fixture) return List.of(fixture.name());
        if (precursor instanceof Precursor.FailingTool broken) {
            return fixtureNames(broken.then());
        }
        return List.of();
    }
}
