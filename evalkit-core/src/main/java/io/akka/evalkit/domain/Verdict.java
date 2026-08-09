package io.akka.evalkit.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One rubric's opinion of one transcript.
 *
 * <p>The rubric id and version travel with the score because without them a score is
 * uninterpretable six weeks later &mdash; see {@link Rubric}.
 *
 * @param reason the judge's own words, and empty under a rubric that asked for a bare
 *               number. What used to sit here was assembled from the band and the score,
 *               which restated the two fields beside it and told a reader nothing they
 *               could act on; {@link RunOutcome#describe()} builds that line where it is
 *               needed, because it is rendering rather than evidence
 */
public record Verdict(String scenarioName, String rubricId, int rubricVersion,
                      int score, Band band, String reason) {

    public Verdict {
        if (score < 1 || score > 10) throw new IllegalArgumentException("score outside 1-10: " + score);
        if (band != Band.of(score)) {
            throw new IllegalArgumentException("band " + band + " does not hold score " + score);
        }
        reason = reason == null ? "" : reason.strip();
    }

    public static Verdict of(String scenarioName, Rubric rubric, int score, String reason) {
        return new Verdict(scenarioName, rubric.id(), rubric.version(), score,
            Band.of(score), reason);
    }

    /** A verdict from a rubric that asked for a bare number, so there is no reason to carry. */
    public static Verdict of(String scenarioName, Rubric rubric, int score) {
        return of(scenarioName, rubric, score, "");
    }

    public boolean statesReason() {
        return !reason.isEmpty();
    }

    /**
     * The verdict a reply carries, read the way its rubric asked for it.
     *
     * <p>Empty means the judge did not answer, which is {@link RunOutcome.Unscoreable} and
     * never a score. A rubric that asked for a reason and got a reply with no label is
     * unreadable here rather than falling back to the bare-number reader: that fallback
     * would take the first integer out of an ordinary sentence and report it as a
     * judgement.
     */
    public static Optional<Verdict> read(String scenarioName, Rubric rubric, String reply) {
        if (rubric.statesReason()) {
            return ModelReply.read(reply)
                .flatMap(stated -> parseScore(stated.score())
                    .map(score -> of(scenarioName, rubric, score, stated.reason())));
        }
        return parseScore(reply).map(score -> of(scenarioName, rubric, score));
    }

    public boolean passed() {
        return band.passed();
    }

    /**
     * The score in a model's reply.
     *
     * <p>The rubric asks for "a single value from 1 to 10" and models mostly comply, but
     * "8", "Score: 8", and "8/10" all occur. Parsing the first bare integer in range is
     * deliberate: a reply this cannot read is returned as empty rather than defaulted,
     * because a default here would be a fabricated judgement, and a fabricated 1 and a
     * fabricated 10 are both worse than an admission that the judge did not answer.
     *
     * <p>A decimal is unreadable rather than truncated. "3.5" carries no integer the rubric
     * asked for, and reading it as 3 rather than 4 moves the run from the undecided band
     * into the failing one on a rounding choice the judge never made.
     */
    private static final Pattern SCORE =
        // Neither side of the match may sit against a digit or a decimal point, so "3.5"
        // yields nothing while "8." and "8/10" still yield 8.
        Pattern.compile("(?<![\\d.])(10|[1-9])\\b(?!\\.\\d)");

    public static Optional<Integer> parseScore(String reply) {
        if (reply == null) return Optional.empty();
        var matcher = SCORE.matcher(reply);
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }
}
