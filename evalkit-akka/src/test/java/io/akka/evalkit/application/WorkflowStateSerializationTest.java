package io.akka.evalkit.application;

import akka.javasdk.testkit.SerializationTestkit;
import io.akka.evalkit.domain.CampaignReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workflow state is written on every transition and read back after a restart.
 *
 * <p>A campaign runs for hours across deploys, so state written by one build is read by
 * the next. Widening {@link CampaignReport} is therefore a compatibility change and not
 * only a code change: a running campaign whose state predates the new columns has to
 * resume, and a campaign that cannot resume loses the hours it already spent.
 */
@DisplayName("CampaignWorkflow.State · surviving a restart and a widened report")
class WorkflowStateSerializationTest {

    private static CampaignWorkflow.State state(CampaignReport tally) {
        return new CampaignWorkflow.State("camp-1", "scenario-judge", 2, 8, 50, 500, 150,
            tally, CampaignWorkflow.Status.RUNNING, List.of("entirely seeded"));
    }

    /**
      * A tally with a distinct value in every column.
      *
      * <p>Twelve positional ints are easy to transpose, so each column carries a value
      * nothing else uses and the assertions below name what they expect.
      */
    private static CampaignReport tally() {
        return new CampaignReport(
            70,   // passed
            4,    // review
            6,    // failed
            3,    // notReached
            2,    // unscoreable
            41,   // walked
            55,   // asserted
            52,   // assertedPassed
            12,   // measured
            11,   // measuredPassed
            9,    // setupFailed
            8);   // noReply
    }

    @Test
    @DisplayName("state survives a round trip unchanged")
    void stateRoundTrips() {
        var before = state(tally());

        var after = SerializationTestkit.deserialize(CampaignWorkflow.State.class,
            SerializationTestkit.serialize(before));

        // Records compare by value, so this covers the cursor, the tally and the notes at
        // once. A field the serialiser drops shows up here rather than after a deploy.
        assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("the report inside the state keeps every column")
    void everyReportColumnSurvives() {
        var after = SerializationTestkit.deserialize(CampaignWorkflow.State.class,
            SerializationTestkit.serialize(state(tally())));

        var tally = after.tally();
        assertThat(tally.measured()).isEqualTo(12);
        assertThat(tally.measuredPassed()).isEqualTo(11);
        assertThat(tally.asserted()).isEqualTo(55);
        assertThat(tally.walked()).isEqualTo(41);
        assertThat(tally.setupFailed()).isEqualTo(9);
        assertThat(tally.noReply()).isEqualTo(8);
    }

    @Test
    @DisplayName("a tally written before the measured columns existed still reads")
    void aTallyFromAnEarlierBuildResumes() {
        // The bytes a build without the measured columns wrote. A campaign paused across
        // that deploy holds state in this shape, and refusing to read it would end the
        // campaign rather than resume it, losing the hours already spent.
        String written = "{\"passed\":70,\"review\":4,\"failed\":6,\"notReached\":3,"
            + "\"unscoreable\":2,\"walked\":41,\"asserted\":55,\"assertedPassed\":52,"
            + "\"setupFailed\":9,\"noReply\":8,\"trustworthy\":true}";

        var after = SerializationTestkit.deserialize(CampaignReport.class, envelope(written));

        assertThat(after.passed()).isEqualTo(70);
        assertThat(after.noReply()).isEqualTo(8);
        // The columns that did not exist read as zero, which is what they counted.
        assertThat(after.measured()).isZero();
        assertThat(after.measuredPassed()).isZero();
    }

    @Test
    @DisplayName("a derived property in the written bytes is ignored on the way back")
    void derivedPropertiesAreIgnored() {
        // The serialiser writes isTrustworthy() as a "trustworthy" property, and the
        // record has no such component. Reading it back has to ignore it rather than
        // fail, or every state written today becomes unreadable tomorrow.
        String withDerived = new String(unwrap(SerializationTestkit.serialize(tally())),
            StandardCharsets.UTF_8);

        assertThat(withDerived).contains("\"trustworthy\"");
        assertThat(SerializationTestkit.deserialize(CampaignReport.class, envelope(withDerived)))
            .isEqualTo(tally());
    }

    /** Wraps a payload in the envelope the serialiser reads. */
    private static byte[] envelope(String json) {
        return ("{\"contentType\":\"json.akka.io/io.akka.evalkit.domain.CampaignReport\","
            + "\"bytes\":\"" + Base64.getEncoder().encodeToString(
                json.getBytes(StandardCharsets.UTF_8)) + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    }

    /** The payload inside an envelope the serialiser wrote. */
    private static byte[] unwrap(byte[] written) {
        String text = new String(written, StandardCharsets.UTF_8);
        String base64 = text.substring(text.indexOf("\"bytes\":\"") + 9, text.lastIndexOf('"'));
        return Base64.getDecoder().decode(base64);
    }

    @Test
    @DisplayName("an empty tally round trips, which is the state a campaign starts from")
    void openingStateRoundTrips() {
        var opening = new CampaignWorkflow.State("camp-2", "scenario-judge", 2, 4, 25, 0, 0,
            CampaignReport.empty(), CampaignWorkflow.Status.CHECKING, List.of());

        var after = SerializationTestkit.deserialize(CampaignWorkflow.State.class,
            SerializationTestkit.serialize(opening));

        assertThat(after).isEqualTo(opening);
        assertThat(after.hasMore()).isFalse();
    }

    @Test
    @DisplayName("a refused campaign keeps the reasons it was refused for")
    void refusedStateKeepsItsReasons() {
        var refused = new CampaignWorkflow.State("camp-3", "scenario-judge", 2, 4, 25, 0, 0,
            CampaignReport.empty(), CampaignWorkflow.Status.REFUSED,
            List.of("target cannot build fixtures [authenticated-claim-open]"));

        var after = SerializationTestkit.deserialize(CampaignWorkflow.State.class,
            SerializationTestkit.serialize(refused));

        // A refusal an hour old should still explain itself to whoever asks why the
        // campaign produced nothing.
        assertThat(after.notes()).containsExactlyElementsOf(refused.notes());
        assertThat(after.status()).isEqualTo(CampaignWorkflow.Status.REFUSED);
    }
}
