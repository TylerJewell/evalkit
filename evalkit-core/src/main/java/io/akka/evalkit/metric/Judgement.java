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
 */
public record Judgement(String subject, boolean affirmed, String reason) {

    public Judgement {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("judgement subject required");
        }
        reason = reason == null ? "" : reason;
    }

    public static Judgement affirmed(String subject) {
        return new Judgement(subject, true, "");
    }

    public static Judgement denied(String subject, String reason) {
        return new Judgement(subject, false, reason);
    }
}
