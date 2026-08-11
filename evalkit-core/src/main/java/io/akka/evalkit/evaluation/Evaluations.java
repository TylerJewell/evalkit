package io.akka.evalkit.evaluation;

import akka.javasdk.evaluation.Evaluation;
import io.akka.evalkit.domain.Band;
import io.akka.evalkit.domain.RunOutcome;
import io.akka.evalkit.domain.Verdict;

import java.util.Optional;

/**
 * Writes an evalkit outcome as the {@link Evaluation} the ledger records.
 *
 * <p>{@link Evaluation#explanation()} takes {@link RunOutcome#describe()}, which renders the
 * band, the score and the judge's own sentence in one line. A reader of the ledger can check
 * that sentence against the interaction it was written about.
 *
 * <p>{@link Evaluation#attributes()} takes the figures a report reads back. {@link #KIND}
 * names which outcome produced the evaluation, so a campaign report keeps deterministic and
 * judged results in separate columns after a round trip through the ledger.
 *
 * <p>{@link Evaluation#passed()} is a boolean and {@link Band} has three states.
 * {@link Band#needsReview()} marks the band a report counts as undecided, and
 * {@link Evaluation#label()} carries the band name so the third state survives.
 */
public final class Evaluations {

    /** Which outcome variant produced an evaluation: {@code scored}, {@code asserted} or {@code measured}. */
    public static final String KIND = "evalkit.kind";

    public static final String SCENARIO = "evalkit.scenario";
    public static final String RUBRIC_ID = "evalkit.rubric.id";
    public static final String RUBRIC_VERSION = "evalkit.rubric.version";
    public static final String METRIC_ID = "evalkit.metric.id";
    public static final String METRIC_VERSION = "evalkit.metric.version";
    public static final String THRESHOLD = "evalkit.threshold";
    public static final String EXPECTED = "evalkit.compared.expected";
    public static final String ACTUAL = "evalkit.compared.actual";

    private Evaluations() {}

    /** A model judgement, with its band in the label and its rubric in the attributes. */
    public static Evaluation of(RunOutcome.Scored scored) {
        Verdict verdict = scored.verdict();
        return Evaluation.of(verdict.passed(), scored.describe())
            .withScore(verdict.score())
            .withLabel(verdict.band().name())
            .withAttribute(KIND, "scored")
            .withAttribute(SCENARIO, verdict.scenarioName())
            .withAttribute(RUBRIC_ID, verdict.rubricId())
            .withAttribute(RUBRIC_VERSION, Integer.toString(verdict.rubricVersion()));
    }

    /**
     * A comparison, which carries no score.
     *
     * <p>A comparison matched or it missed, so no figure belongs beside it. Writing one would
     * put a number in a cell a report prints as a dash.
     */
    public static Evaluation of(RunOutcome.Asserted asserted) {
        return Evaluation.of(asserted.passed(), asserted.describe())
            .withAttribute(KIND, "asserted")
            .withAttribute(EXPECTED, asserted.expected())
            .withAttribute(ACTUAL, asserted.actual());
    }

    /** A metric, with its value as the score and its threshold beside it. */
    public static Evaluation of(RunOutcome.Measured measured) {
        return Evaluation.of(measured.withinThreshold(), measured.describe())
            .withScore(measured.value())
            .withAttribute(KIND, "measured")
            .withAttribute(METRIC_ID, measured.metricId())
            .withAttribute(METRIC_VERSION, Integer.toString(measured.metricVersion()))
            .withAttribute(THRESHOLD, Double.toString(measured.threshold()));
    }

    /**
     * The verdict an evaluation holds, or nothing when it holds no readable one.
     *
     * <p>{@link Evaluation#score()} is an unbounded {@code Optional<Double>} and
     * {@link Verdict} pairs a 1-to-10 score with its band. A score outside that range, or a
     * label naming no band, produces {@link RunOutcome.Unscoreable} instead of throwing.
     */
    public static RunOutcome read(Evaluation evaluation) {
        if (!"scored".equals(evaluation.attributes().get(KIND))) {
            return new RunOutcome.Unscoreable(
                "the evaluation records no model judgement: " + evaluation.explanation());
        }
        Optional<Double> score = evaluation.score();
        if (score.isEmpty()) {
            return new RunOutcome.Unscoreable("the evaluation carries no score");
        }
        double value = score.get();
        int whole = (int) Math.round(value);
        if (value != whole || whole < 1 || whole > 10) {
            return new RunOutcome.Unscoreable(
                "the evaluation scores " + value + ", which is outside the 1 to 10 a band holds");
        }
        var attributes = evaluation.attributes();
        var verdict = new Verdict(
            attributes.getOrDefault(SCENARIO, ""),
            attributes.getOrDefault(RUBRIC_ID, ""),
            Integer.parseInt(attributes.getOrDefault(RUBRIC_VERSION, "1")),
            whole, Band.of(whole), evaluation.explanation());
        return new RunOutcome.Scored(verdict);
    }
}
