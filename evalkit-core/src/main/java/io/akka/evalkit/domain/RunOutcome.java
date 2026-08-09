package io.akka.evalkit.domain;

/**
 * What happened to one scenario run, which is not always "it got a score".
 *
 * <p>Three of these are not judgements about the system, and folding them into a low score
 * is the most consequential mistake this harness can make &mdash; each one would report a
 * broken harness, a refused judge, or an undecidable rubric as a failing product.
 *
 * <p>Both non-scored variants are evidence-backed rather than defensive. {@link NotReached}
 * exists because 502 scenarios need setup that can fail. {@link Unscoreable} exists because
 * it already happened: during calibration Gemini's content filter refused to score a
 * GenUC-02 identification-failure transcript, and a harness that dropped it silently would
 * have reported better agreement than it earned.
 */
public sealed interface RunOutcome {

    /** The judge returned a verdict. */
    record Scored(Verdict verdict) implements RunOutcome {}

    /**
     * Settled by comparison rather than by a model.
     *
     * <p>A scenario naming a specification node is exercising a decision that is a pure
     * function here: it either reached that node or it did not. Sending it to a judge
     * would buy a slightly random opinion about something with a right answer, and pay
     * for it. 510 of the 514 claim-flow scenarios in the corpus are this kind.
     */
    record Asserted(boolean passed, String expectedNode, String actualNode)
        implements RunOutcome {}

    /**
     * Settled by computing a number over the reply and comparing it to a threshold.
     *
     * <p>Repeatable in execution and approximate in meaning. The same reply produces the
     * same number on every run, and the number a person chose for the threshold decides
     * what that number means.
     *
     * <p>The metric id and version travel with the result for the reason a rubric version
     * travels with a verdict: raising a threshold from 0.75 to 0.80 turns passing runs
     * into failing ones with no change to the system under test, and a bare number six
     * weeks later cannot tell the two apart.
     */
    record Measured(String metricId, int metricVersion, double value,
                    double threshold, boolean withinThreshold, String reason)
        implements RunOutcome {

        public Measured {
            if (metricId == null || metricId.isBlank()) {
                throw new IllegalArgumentException("measured outcome needs a metric id");
            }
            if (metricVersion < 1) throw new IllegalArgumentException("metric version starts at 1");
            reason = reason == null ? "" : reason.strip();
        }

        /**
         * A measurement with nothing to say beyond the number.
         *
         * <p>Which is most of them. A metric that counts set membership has no reasoning to
         * report past the count, and a metric that asked a model for one score has the
         * model's sentence &mdash; the same rule {@link io.akka.evalkit.metric.Judgement}
         * states for a single judgement.
         */
        public Measured(String metricId, int metricVersion, double value,
                        double threshold, boolean withinThreshold) {
            this(metricId, metricVersion, value, threshold, withinThreshold, "");
        }

        public boolean statesReason() {
            return !reason.isEmpty();
        }
    }

    /** Why a run produced nothing, decided where it happened rather than parsed after. */
    enum Cause {
        /** The starting state could not be built, so nothing was ever asked. */
        SETUP_FAILED,
        /** The system was asked and did not answer in time. */
        NO_REPLY
    }

    /** The precursor never put the target in the state under test. */
    record NotReached(Cause cause, String reason, Precursor precursor) implements RunOutcome {}

    /** The judge could not or would not answer: a filter, a timeout, an unparseable reply. */
    record Unscoreable(String reason) implements RunOutcome {}

    /**
     * The scorer itself broke, so no judgement was ever reached.
     *
     * <p>Distinct from {@link Unscoreable}, which is a scorer that ran and declined. A judge
     * refusing a transcript is a fact about the transcript; a metric throwing a
     * {@code NullPointerException} is a fact about this kit. Reporting both as "the judge did
     * not answer" hides a defect here inside a caveat about the subject, and a defect that
     * reads as a caveat is a defect nobody fixes.
     *
     * <p>Neither counts toward a pass rate. They are separated so that a campaign whose
     * unscored runs are all this variant is recognisable as a broken campaign rather than a
     * difficult corpus.
     */
    record ScorerFailed(String reason) implements RunOutcome {}

    /**
     * Whether this counts toward a pass rate at all.
     *
     * <p>A run that never reached its state and a run nothing would score are both absent
     * evidence. They belong in the denominator of "did we test what we meant to", never in
     * the denominator of "did the system answer correctly".
     */
    default boolean isEvidence() {
        return this instanceof Scored || this instanceof Asserted || this instanceof Measured;
    }

    default boolean passed() {
        return switch (this) {
            case Scored s -> s.verdict().passed();
            case Asserted a -> a.passed();
            case Measured m -> m.withinThreshold();
            case NotReached ignored -> false;
            case Unscoreable ignored -> false;
            case ScorerFailed ignored -> false;
        };
    }

    default boolean needsReview() {
        return this instanceof Scored s && s.verdict().band().needsReview();
    }

    /** A row's detail, with whatever the producer stated appended to it. */
    private static String with(String detail, String reason) {
        return reason.isEmpty() ? detail : detail + " — " + reason;
    }

    default String describe() {
        return switch (this) {
            // The band and the score, which is the whole of what a rubric asking for a bare
            // number returns. A rubric that asks for a reason adds the judge's sentence.
            case Scored s -> with(
                s.verdict().band() + " (" + s.verdict().score() + "/10)", s.verdict().reason());
            case Asserted a -> a.passed()
                ? "reached " + a.expectedNode()
                : "expected " + a.expectedNode() + ", reached " + a.actualNode();
            case Measured m -> with("%s v%d: %.2f against %.2f".formatted(
                m.metricId(), m.metricVersion(), m.value(), m.threshold()), m.reason());
            case NotReached n -> switch (n.cause()) {
                case SETUP_FAILED -> "never reached the question — " + n.reason();
                case NO_REPLY -> "no reply — " + n.reason();
            };
            case Unscoreable u -> "unscoreable — " + u.reason();
            case ScorerFailed f -> "the scorer failed — " + f.reason();
        };
    }
}
