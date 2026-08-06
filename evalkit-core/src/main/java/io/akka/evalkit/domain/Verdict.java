package io.akka.evalkit.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One rubric's opinion of one transcript.
 *
 * <p>The rubric id and version travel with the score because without them a score is
 * uninterpretable six weeks later &mdash; see {@link Rubric}.
 */
public record Verdict(String scenarioName, String rubricId, int rubricVersion,
                      int score, Band band, String rationale) {

    public Verdict {
        if (score < 1 || score > 10) throw new IllegalArgumentException("score outside 1-10: " + score);
        if (band != Band.of(score)) {
            throw new IllegalArgumentException("band " + band + " does not hold score " + score);
        }
        rationale = rationale == null ? "" : rationale;
    }

    public static Verdict of(String scenarioName, Rubric rubric, int score, String rationale) {
        return new Verdict(scenarioName, rubric.id(), rubric.version(), score,
            Band.of(score), rationale);
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
     */
    public static Optional<Integer> parseScore(String reply) {
        if (reply == null) return Optional.empty();
        var matcher = Pattern.compile("\\b(10|[1-9])\\b").matcher(reply);
        return matcher.find() ? Optional.of(Integer.parseInt(matcher.group(1))) : Optional.empty();
    }
}
