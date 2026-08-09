package io.akka.evalkit.metric;

/**
 * One decision about one part of a reply.
 *
 * <p>A claim was supported or it was not. An exchange was relevant or it was not. A tool
 * call was authorised or it was not. Metrics differ in what they judge and in how the
 * judgements are produced; they agree on this shape.
 *
 * @param subject what was judged, named well enough to appear in a report row
 * @param affirmed whether the judgement went the metric's way
 * @param reason why, when the producer supplies one. Empty for a comparison, which has
 *               no reasoning to report beyond the comparison itself
 * @param credit how much of the judgement went the metric's way, between 0 and 1. One and
 *               zero for the yes-or-no judgements, which is nearly all of them. A tool
 *               called with three of its four arguments right is the case that needs the
 *               rest of the range: rounding it to a yes or a no changes the score the
 *               metric was defined to produce, and a metric aggregating booleans has no
 *               way to carry the difference
 */
public record Judgement(String subject, boolean affirmed, String reason, double credit) {

    public Judgement {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("judgement subject required");
        }
        if (credit < 0 || credit > 1) {
            throw new IllegalArgumentException("credit outside 0-1: " + credit);
        }
        reason = reason == null ? "" : reason;
    }

    /** A yes-or-no judgement, which carries all of the credit or none of it. */
    public Judgement(String subject, boolean affirmed, String reason) {
        this(subject, affirmed, reason, affirmed ? 1.0 : 0.0);
    }

    public static Judgement affirmed(String subject) {
        return new Judgement(subject, true, "");
    }

    public static Judgement denied(String subject, String reason) {
        return new Judgement(subject, false, reason);
    }

    /** Part of the way there, and affirmed only when all of the way. */
    public static Judgement partial(String subject, double credit, String reason) {
        return new Judgement(subject, credit >= 1.0, reason, credit);
    }
}
