package io.akka.evalkit.domain;

import java.util.Objects;

/**
 * One conversation, already produced, waiting to be scored.
 *
 * <p><b>The separation this type exists to enforce.</b> Execution makes transcripts;
 * scoring reads them. Keeping them apart is what lets a rubric be re-run over history
 * without paying for the conversation again &mdash; and it is the only way to compare two
 * systems honestly, because the same rubric version can be applied to a transcript this
 * harness produced and to one recorded from somewhere else. A harness that scores as it
 * executes can never answer "how would the old system have done under the new rubric?".
 *
 * <p>The four fields are the four placeholders the Scenario Judge interpolates. {@code
 * replayHistory} is the setup that put the agent in the state under test; {@code
 * simulationHistory} is what is actually being graded.
 */
public record Transcript(String scenarioName,
                         String replayHistory,
                         String simulationHistory,
                         String systemOutput,
                         String expectedOutcome) {

    public Transcript {
        Objects.requireNonNull(scenarioName, "scenarioName");
        Objects.requireNonNull(expectedOutcome, "expectedOutcome");
        replayHistory = replayHistory == null ? "" : replayHistory;
        simulationHistory = simulationHistory == null ? "" : simulationHistory;
        systemOutput = systemOutput == null ? "" : systemOutput;

        if (simulationHistory.isBlank() && systemOutput.isBlank()) {
            // Nothing was graded. Scoring this would produce a low score that reads as a
            // failing system when in fact the run never happened — the distinction the
            // harness exists to keep, so it is refused at construction.
            throw new IllegalArgumentException(
                "transcript has neither a simulation nor an output: " + scenarioName);
        }
    }

    /** Whether the agent had to be walked into position before the graded turn. */
    public boolean hasSetup() {
        return !replayHistory.isBlank();
    }
}
