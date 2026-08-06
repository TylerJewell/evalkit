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
     * Whether this counts toward a pass rate at all.
     *
     * <p>A run that never reached its state and a run nothing would score are both absent
     * evidence. They belong in the denominator of "did we test what we meant to", never in
     * the denominator of "did the system answer correctly".
     */
    default boolean isEvidence() {
        return this instanceof Scored || this instanceof Asserted;
    }

    default boolean passed() {
        return switch (this) {
            case Scored s -> s.verdict().passed();
            case Asserted a -> a.passed();
            case NotReached ignored -> false;
            case Unscoreable ignored -> false;
        };
    }

    default boolean needsReview() {
        return this instanceof Scored s && s.verdict().band().needsReview();
    }

    default String describe() {
        return switch (this) {
            // Score only. Rubric v2 asks the judge for a bare 1-10 and nothing else, so
            // a scored outcome has no reason attached to print — see ScenarioJudge.
            case Scored s -> s.verdict().band() + " (" + s.verdict().score() + "/10)";
            case Asserted a -> a.passed()
                ? "reached " + a.expectedNode()
                : "expected " + a.expectedNode() + ", reached " + a.actualNode();
            case NotReached n -> switch (n.cause()) {
                case SETUP_FAILED -> "never reached the question — " + n.reason();
                case NO_REPLY -> "no reply — " + n.reason();
            };
            case Unscoreable u -> "unscoreable — " + u.reason();
        };
    }
}
