package io.akka.evalkit.domain;

import java.util.Objects;

/**
 * One finished run, waiting to be scored.
 *
 * <p>What a scorer receives. Execution produces recordings and scoring reads them, so a
 * rubric applies to stored recordings without running the conversations again, and one
 * rubric version scores a recording this kit produced beside a recording made elsewhere.
 *
 * @param transcript the four fields a rubric interpolates
 * @param evidence   everything else the run observed, which a metric reads and a judge
 *                   never sees
 */
public record Recording(Transcript transcript, Evidence evidence) {

    public Recording {
        Objects.requireNonNull(transcript, "transcript");
        evidence = evidence == null ? Evidence.NONE : evidence;
    }

    /** A recording carrying nothing beyond the transcript, which is what a judge needs. */
    public static Recording of(Transcript transcript) {
        return new Recording(transcript, Evidence.NONE);
    }

    /** The scenario this run came from, for a report row. */
    public String scenarioName() {
        return transcript.scenarioName();
    }
}
