package io.akka.evalkit.application;

import io.akka.evalkit.domain.Band;
import io.akka.evalkit.domain.Rubric;
import io.akka.evalkit.domain.Transcript;
import akka.javasdk.testkit.TestKitSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The judge against a real model, on transcripts written for this test.
 *
 * <p>{@link ScenarioJudgeTest} proves the parsing and the banding with a stubbed model, and
 * {@link JudgeCalibrationTest} measures agreement against a reference corpus. Between them
 * sits the path nothing else covers: the rubric reaching a provider, the provider answering
 * in the shape the rubric asked for, and {@code ModelReply} reading that answer. A rubric
 * that asks for two labelled lines is worth nothing until a model has been asked for them.
 *
 * <p><b>This establishes that the path works. It establishes nothing about agreement.</b>
 * The transcripts below were written to have obvious answers, and four of them are not a
 * sample. Whether v3 scores as v2 scores is {@link JudgeCalibrationTest}'s question, on a
 * corpus with reference scores behind it.
 *
 * <p>Opt-in, because it calls a live model and costs money:
 *
 * <pre>
 * mvn test -pl evalkit-akka -Dtest=LiveJudgeTest -Dlive=true
 * </pre>
 *
 * <p>The provider comes from {@code application.conf} and the key from the environment.
 */
@DisplayName("Live judge · the rubric against a real model")
@EnabledIfSystemProperty(named = "live", matches = "true")
class LiveJudgeTest extends TestKitSupport {

    private static final Rubric BARE = Rubric.load("scenario-judge", 2);
    private static final Rubric REASONED = Rubric.load("scenario-judge", 3);

    /** Transcripts written for this test, each with an answer a reader would agree on. */
    private static final List<Transcript> TRANSCRIPTS = List.of(
        new Transcript("refund-refused-outside-window",
            "User: I want a refund\nAgent: which order?",
            "User: order 4417, it has been 45 days\n"
                + "Agent: that is outside the 30-day return window, so I cannot refund it",
            "that is outside the 30-day return window, so I cannot refund it",
            "Refuses the refund and states the 30-day window"),

        new Transcript("refund-granted-when-it-should-refuse",
            "User: I want a refund\nAgent: which order?",
            "User: order 4417, it has been 45 days\n"
                + "Agent: no problem, I have issued your refund",
            "no problem, I have issued your refund",
            "Refuses the refund and states the 30-day window"),

        new Transcript("window-named-without-a-decision",
            "User: I want a refund\nAgent: which order?",
            "User: order 4417, it has been 45 days\n"
                + "Agent: our return window is 30 days. Is there anything else?",
            "our return window is 30 days. Is there anything else?",
            "Refuses the refund and states the 30-day window"),

        new Transcript("delivery-status-answered",
            "",
            "User: where is my parcel?\nAgent: it was delivered on Tuesday and signed for by R. Patel",
            "it was delivered on Tuesday and signed for by R. Patel",
            "States the delivery date and who signed for it"));

    private record Judged(String scenario, int bare, int reasoned, String reason) {}

    @Test
    @DisplayName("both rubrics score, and v3 says why")
    void judgeUnderBothRubrics() {
        var judged = new ArrayList<Judged>();

        for (Transcript transcript : TRANSCRIPTS) {
            var bare = judge(transcript, BARE);
            var reasoned = judge(transcript, REASONED);
            judged.add(new Judged(transcript.scenarioName(), bare.score(), reasoned.score(),
                reasoned.reason()));
        }

        System.out.println("\n============ live judge, v2 against v3 ============");
        System.out.printf("%-38s  %-14s%-14s%n", "scenario", "v2", "v3");
        for (Judged j : judged) {
            System.out.printf("%-38s  %-14s%-14s%n", j.scenario(),
                j.bare() + " " + Band.of(j.bare()), j.reasoned() + " " + Band.of(j.reasoned()));
            System.out.println("    v3 said: " + j.reason());
        }
        long sameBand = judged.stream()
            .filter(j -> Band.of(j.bare()) == Band.of(j.reasoned())).count();
        System.out.printf("%nsame band on %d of %d, which is too few to mean anything "
            + "about agreement%n", sameBand, judged.size());

        // What this run is allowed to assert. Every v3 reply parsed, and every one carried
        // the sentence the rubric asked for. Where the scores landed is not asserted,
        // because a model's score is not this test's to fix.
        assertThat(judged).allSatisfy(j -> {
            assertThat(j.reason()).as("v3 reason for " + j.scenario()).isNotBlank();
            assertThat(j.reasoned()).isBetween(1, 10);
            assertThat(j.bare()).isBetween(1, 10);
        });
    }

    private ScenarioJudge.Result judge(Transcript transcript, Rubric rubric) {
        return componentClient.forAgent()
            .inSession("live-v" + rubric.version() + "-" + transcript.scenarioName())
            .method(ScenarioJudge::judge)
            .invoke(new ScenarioJudge.JudgeRequest(transcript, rubric));
    }
}
