package io.akka.evalkit.application;

import akka.javasdk.testkit.TestKitSupport;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CampaignWorkflow · a campaign that survives being interrupted")
class CampaignWorkflowTest extends TestKitSupport {

    @BeforeEach
    void reset() {
        TestHarnessSetup.Source.SIZES.clear();
        TestHarnessSetup.Source.BAD_FIXTURE.clear();
        TestHarnessSetup.Source.PAGES.set(0);
        TestHarnessSetup.Judge.judged.set(0);
        TestHarnessSetup.Judge.score = 9;
    }

    private String campaign(int size) {
        String id = "camp-" + UUID.randomUUID();
        TestHarnessSetup.Source.SIZES.put(id, size);
        return id;
    }

    private CampaignWorkflow.State start(String id, int lanes, int wave) {
        componentClient.forWorkflow(id).method(CampaignWorkflow::start)
            .invoke(new CampaignWorkflow.Start(id, "scenario-judge", 2, lanes, wave));
        return await(id);
    }

    private CampaignWorkflow.State await(String id) {
        Awaitility.await().atMost(60, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(state(id).status()).isIn(CampaignWorkflow.Status.COMPLETE,
                CampaignWorkflow.Status.REFUSED));
        return state(id);
    }

    private CampaignWorkflow.State state(String id) {
        return componentClient.forWorkflow(id).method(CampaignWorkflow::get).invoke();
    }

    @Test
    @DisplayName("a campaign runs every scenario and totals them across waves")
    void runsToCompletion() {
        var id = campaign(25);

        var state = start(id, 4, 10);

        assertThat(state.status()).isEqualTo(CampaignWorkflow.Status.COMPLETE);
        assertThat(state.cursor()).isEqualTo(25);
        assertThat(state.tally().judged()).isEqualTo(25);
        assertThat(state.tally().passed()).isEqualTo(25);
        assertThat(TestHarnessSetup.Judge.judged.get()).isEqualTo(25);
    }

    @Test
    @DisplayName("the corpus is read a page at a time, never whole")
    void pagesRatherThanLoads() {
        // Workflow state is serialised on every transition. Carrying five thousand
        // scenarios through each one would cost more than running them.
        var id = campaign(30);

        start(id, 2, 10);

        // One page for the pre-flight check, then one per wave.
        assertThat(TestHarnessSetup.Source.PAGES.get()).isBetween(4, 6);
    }

    @Test
    @DisplayName("progress is durable between waves, so a restart costs one wave")
    void progressIsDurable() {
        var id = campaign(20);
        var state = start(id, 2, 5);

        // Four waves of five. Recovery would resume from the cursor, not from zero.
        assertThat(state.cursor()).isEqualTo(20);
        assertThat(state.progressPercent()).isEqualTo(100);
    }

    @Test
    @DisplayName("a wave larger than the corpus is one wave, not an error")
    void waveLargerThanCorpus() {
        var state = start(campaign(3), 4, 500);

        assertThat(state.tally().judged()).isEqualTo(3);
        assertThat(state.status()).isEqualTo(CampaignWorkflow.Status.COMPLETE);
    }

    @Test
    @DisplayName("a campaign naming a fixture the target lacks is refused, and says why")
    void refusedForMissingFixture() {
        var id = campaign(10);
        TestHarnessSetup.Source.BAD_FIXTURE.add(id);

        var state = start(id, 2, 5);

        assertThat(state.status()).isEqualTo(CampaignWorkflow.Status.REFUSED);
        assertThat(state.notes()).anyMatch(n -> n.contains("nonexistent"));
        // Refused before anything was judged — the point of checking first.
        assertThat(TestHarnessSetup.Judge.judged.get()).isZero();
    }

    @Test
    @DisplayName("a refusal stays inspectable, so 'why did this produce nothing' has an answer")
    void refusalIsDurable() {
        var id = campaign(0);

        var state = start(id, 2, 5);

        assertThat(state.status()).isEqualTo(CampaignWorkflow.Status.REFUSED);
        assertThat(state.notes()).anyMatch(n -> n.contains("no scenarios"));
        assertThat(state(id).notes()).isEqualTo(state.notes());
    }

    @Test
    @DisplayName("a completed campaign carries its caveats, not just its counts")
    void carriesCaveats() {
        // Everything seeded and everything undecided: two reasons the numbers must not
        // be quoted bare.
        TestHarnessSetup.Judge.score = 5;
        var state = start(campaign(10), 2, 5);

        assertThat(state.tally().review()).isEqualTo(10);
        assertThat(state.notes()).anyMatch(n -> n.contains("not quotable"));
        assertThat(state.notes()).anyMatch(n -> n.contains("entirely seeded"));
    }

    @Test
    @DisplayName("starting twice is refused rather than doubling the tally")
    void startedOnce() {
        var id = campaign(5);
        start(id, 2, 5);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            componentClient.forWorkflow(id).method(CampaignWorkflow::start)
                .invoke(new CampaignWorkflow.Start(id, "scenario-judge", 2, 2, 5)))
            .hasMessageContaining("already started");
    }
}
