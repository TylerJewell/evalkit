package io.akka.evalkit.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes a scenario. Does not judge it.
 *
 * <p>The separation is the point &mdash; see {@link Transcript}. This produces the
 * artefact; scoring is a later, independently repeatable step, which is what allows a
 * rubric to change without re-running anything and allows two systems to be compared on
 * one ruler.
 *
 * <p>Pure, and deliberately so. Everything that touches a network is behind
 * {@link SystemUnderTest}, so the orchestration &mdash; which is where the interesting
 * mistakes live &mdash; is testable without a model, a service, or a clock.
 */
public final class ScenarioRunner {

    private ScenarioRunner() {}

    /** What execution produced, which is not always a transcript. */
    public sealed interface Execution {

        record Produced(Transcript transcript, java.util.Optional<String> node)
            implements Execution {}

        record NotReached(RunOutcome.Cause cause, String reason) implements Execution {}
    }

    /**
     * Reach the state, say the graded turn, return the transcript.
     *
     * <p>Nothing here catches exceptions from {@link SystemUnderTest#submit}. A target
     * that throws while answering is broken in a way the harness should not smooth over
     * into a low score.
     */
    public static Execution execute(Scenario scenario, SystemUnderTest target) {
        var unknown = missingFixture(scenario.precursor(), target);
        if (unknown != null) {
            return new Execution.NotReached(RunOutcome.Cause.SETUP_FAILED,
                "no fixture named '" + unknown + "' — known: " + target.fixtures().keySet());
        }

        var prepared = target.prepare(scenario.precursor());
        if (prepared instanceof SystemUnderTest.Prepared.Failed failed) {
            return new Execution.NotReached(RunOutcome.Cause.SETUP_FAILED,
                scenario.precursor().describe() + " did not land: " + failed.reason());
        }

        var ready = (SystemUnderTest.Prepared.Ready) prepared;
        var reply = target.submit(ready.sessionId(), scenario.gradedTurn());
        String answer = reply == null ? null : reply.text();

        if (answer == null || answer.isBlank()) {
            // A silent system is not a wrong answer; there is nothing to grade, and
            // scoring emptiness would produce a low score that reads as a bad reply.
            return new Execution.NotReached(RunOutcome.Cause.NO_REPLY,
                "the system returned nothing to the graded turn");
        }

        var graded = "User: " + scenario.gradedTurn() + "\n\nAgent: " + answer;
        return new Execution.Produced(new Transcript(
            scenario.id(), ready.historySoFar(), graded, answer, scenario.expectedOutcome()),
            reply.node());
    }

    /**
     * Fixtures a campaign needs that the target does not have.
     *
     * <p>Checked up front so a campaign is refused in seconds rather than yielding a few
     * hundred not-reached results at the end of a long run.
     */
    public static List<String> unsupported(List<Scenario> scenarios, SystemUnderTest target) {
        var missing = new ArrayList<String>();
        for (Scenario scenario : scenarios) {
            String name = missingFixture(scenario.precursor(), target);
            if (name != null && !missing.contains(name)) missing.add(name);
        }
        missing.sort(String::compareTo);
        return missing;
    }

    private static String missingFixture(Precursor precursor, SystemUnderTest target) {
        return precursor instanceof Precursor.Fixture fixture
            && !target.fixtures().containsKey(fixture.name())
            ? fixture.name()
            : null;
    }
}
