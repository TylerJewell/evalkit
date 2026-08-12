package io.akka.evalkit.metric;

/**
 * One decision about one part of a reply.
 *
 * <p>A claim was supported or it was not. An exchange was relevant or it was not. A tool
 * call was authorised or it was not. Metrics differ in what they judge and in how the
 * findings are produced; they agree on this shape.
 *
 * <p>The claim is what this decides about, and it is not
 * {@link akka.javasdk.evaluation.Subject}, which names the interaction the whole evaluation
 * ran over. One subject carries many claims.
 *
 * @param claim what was judged, named well enough to appear in a report row
 * @param affirmed whether the finding went the metric's way
 * @param explanation why, when the producer supplies one. Empty for a comparison, which has
 *               no reasoning to report beyond the comparison itself
 * @param credit how much of the finding went the metric's way, between 0 and 1. One and
 *               zero for the yes-or-no findings, which is nearly all of them. A tool
 *               called with three of its four arguments right is the case that needs the
 *               rest of the range: rounding it to a yes or a no changes the score the
 *               metric was defined to produce, and a metric aggregating booleans has no
 *               way to carry the difference
 */
public record Finding(String claim, boolean affirmed, String explanation, double credit) {

    public Finding {
        if (claim == null || claim.isBlank()) {
            throw new IllegalArgumentException("finding claim required");
        }
        if (credit < 0 || credit > 1) {
            throw new IllegalArgumentException("credit outside 0-1: " + credit);
        }
        explanation = explanation == null ? "" : explanation;
    }

    /** A yes-or-no finding, which carries all of the credit or none of it. */
    public Finding(String claim, boolean affirmed, String explanation) {
        this(claim, affirmed, explanation, affirmed ? 1.0 : 0.0);
    }

    public static Finding affirmed(String claim) {
        return new Finding(claim, true, "");
    }

    public static Finding denied(String claim, String explanation) {
        return new Finding(claim, false, explanation);
    }

    /** Part of the way there, and affirmed only when all of the way. */
    public static Finding partial(String claim, double credit, String explanation) {
        return new Finding(claim, credit >= 1.0, explanation, credit);
    }
}
