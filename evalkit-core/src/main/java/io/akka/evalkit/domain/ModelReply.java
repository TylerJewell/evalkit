package io.akka.evalkit.domain;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A reply from a model that was asked for a score and the reason behind it.
 *
 * <p>Two callers read replies this way and they disagree about the scale. A rubric judge
 * scores 1 to 10 in bands; an alignment metric scores 0 to 1 against a threshold. Only the
 * labelled shape is shared, so this returns the score as text and each caller converts it.
 * Sharing the scale instead would put a banded verdict and a continuous measurement in one
 * type, and the first thing anybody would do with that type is average across the two.
 *
 * <p><b>A label is required, not preferred.</b> A rubric that asks for a bare number is read
 * by {@link Verdict#parseScore}, which finds the first integer in range anywhere in the
 * reply. Applying that to a rubric that asked for two fields would read the first number of
 * a sentence as the score, so a reply with no {@code SCORE} label is unreadable here rather
 * than guessed at.
 *
 * <p>A reason that never arrived leaves {@link Stated#reason()} blank and keeps the score.
 * The score is evidence whatever the model did with the second line, and a campaign that
 * loses its reasons shows up as a run of blank reasons in calibration rather than as a
 * campaign of unscoreable runs.
 */
public final class ModelReply {

    private ModelReply() {}

    /**
     * @param score  the value as the model wrote it, for the caller to read on its own scale
     * @param reason the model's words, blank when it wrote none
     */
    public record Stated(String score, String reason) {

        public Stated {
            if (score == null || score.isBlank()) {
                throw new IllegalArgumentException("a stated reply needs a score");
            }
            score = score.strip();
            reason = reason == null ? "" : reason.strip();
        }

        public boolean statesReason() {
            return !reason.isEmpty();
        }
    }

    // Asterisks because a model asked for "SCORE:" returns "**SCORE:**" often enough to
    // matter, and a run lost to markdown is a run lost to nothing.
    private static final Pattern SCORE =
        Pattern.compile("(?im)^\\s*\\**\\s*SCORE\\s*\\**\\s*:\\s*\\**\\s*(.+?)\\s*\\**\\s*$");

    // Runs to the end of the reply, so a reason spanning several lines survives whole. It
    // stops at a later score label, because a model that writes the reason first would
    // otherwise fold its own score into the sentence it belongs to.
    private static final Pattern REASON = Pattern.compile(
        "(?ims)^\\s*\\**\\s*REASON\\s*\\**\\s*:\\s*(.+?)(?=^\\s*\\**\\s*SCORE\\s*\\**\\s*:|\\z)");

    /** The labelled fields, or empty when the reply carries no score label. */
    public static Optional<Stated> read(String reply) {
        if (reply == null) return Optional.empty();
        Matcher score = SCORE.matcher(reply);
        if (!score.find()) return Optional.empty();

        Matcher reason = REASON.matcher(reply);
        String stated = reason.find() ? stripMarkdown(reason.group(1)) : "";
        return Optional.of(new Stated(stripMarkdown(score.group(1)), stated));
    }

    private static String stripMarkdown(String text) {
        return text.replaceAll("\\*+", "").strip();
    }
}
