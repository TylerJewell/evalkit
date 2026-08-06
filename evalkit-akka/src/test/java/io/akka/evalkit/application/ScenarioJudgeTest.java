package io.akka.evalkit.application;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import akka.javasdk.testkit.TestModelProvider;
import io.akka.evalkit.domain.Band;
import io.akka.evalkit.domain.Rubric;
import io.akka.evalkit.domain.Transcript;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The judge, with no model behind it.
 *
 * <p>A harness that can only be tested by calling a model inherits every property that
 * makes model output hard to test &mdash; cost, latency, non-determinism, and failures
 * unrelated to the code. The thing doing the evaluating should be held to the standard it
 * is being built to enforce.
 */
@DisplayName("ScenarioJudge · scoring a transcript")
class ScenarioJudgeTest extends TestKitSupport {

    private final TestModelProvider model = new TestModelProvider();

    @Override
    protected TestKit.Settings testKitSettings() {
        return TestKit.Settings.DEFAULT.withModelProvider(ScenarioJudge.class, model);
    }

    private static final Rubric RUBRIC = Rubric.load("scenario-judge", 2);

    private static final Transcript TRANSCRIPT = new Transcript(
        "GenUC-17a: Single pax chooses cash payment (Country = Canada)",
        "User: I want to claim\nAgent: which passenger?",
        "User: cash please\nAgent: payment will be made via Interac e-transfer",
        "payment will be made via Interac e-transfer",
        "The agent should inform the user that the payment will be made via Interac e-transfer.");

    private ScenarioJudge.Result judge() {
        return componentClient.forAgent()
            .inSession("test-session")
            .method(ScenarioJudge::judge)
            .invoke(new ScenarioJudge.JudgeRequest(TRANSCRIPT, RUBRIC));
    }

    @Test
    @DisplayName("a faithful match scores in band and passes")
    void faithful() {
        model.fixedResponse("9");

        var result = judge();

        assertThat(result.score()).isEqualTo(9);
        assertThat(result.band()).isEqualTo(Band.FAITHFUL);
        assertThat(result.passed()).isTrue();
        assertThat(result.explanation()).contains("scenario-judge v2");
    }

    @Test
    @DisplayName("a partial match does not pass, even at the top of its band")
    void partial() {
        model.fixedResponse("7");

        var result = judge();

        assertThat(result.band()).isEqualTo(Band.PARTIAL);
        assertThat(result.passed()).isFalse();
    }

    @Test
    @DisplayName("the score is read out of a reply that is not bare")
    void notBare() {
        model.fixedResponse("Score: 2");

        assertThat(judge().band()).isEqualTo(Band.NO_MATCH);
    }

    @Test
    @DisplayName("a judge that returns no score fails the run rather than defaulting it")
    void noScore() {
        // The alternative is a fabricated judgement quietly shifting a band distribution.
        model.fixedResponse("I am unable to assess this conversation.");

        assertThatThrownBy(this::judge)
            .hasMessageContaining("no score");
    }

    @Test
    @DisplayName("the transcript reaches the model inside the rubric, not beside it")
    void promptCarriesTheTranscript() {
        // Guards the interpolation actually happening on the path the agent uses — a
        // rubric rendered with empty placeholders still returns plausible scores.
        model.whenMessage(m -> m.contains("payment will be made via Interac e-transfer")
                             && m.contains("Output a single value from 1 to 10"))
            .reply("10");

        assertThat(judge().score()).isEqualTo(10);
    }
}
