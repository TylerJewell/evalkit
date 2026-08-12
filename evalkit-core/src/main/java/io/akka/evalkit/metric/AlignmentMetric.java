package io.akka.evalkit.metric;

import io.akka.evalkit.domain.ModelReply;
import io.akka.evalkit.domain.Observation;
import io.akka.evalkit.domain.RunOutcome;
import io.akka.evalkit.domain.Scorer;

import java.util.Optional;

/**
 * A score a model produces by reading one thing against another.
 *
 * <p>DeepEval's trace-level agentic metrics share a formula:
 * {@code AlignmentScore(task, something)}, where the something is the outcome, the plan or
 * the steps. One model call returns one score and one sentence, which is why these are not
 * {@link Metric}s: there is no list of findings to aggregate, so the arithmetic that
 * {@link Metric#aggregate} exists to keep testable does not exist here.
 *
 * <p><b>What that costs, stated once.</b> A metric whose score is a model's opinion inherits
 * everything a judged run inherits &mdash; it does not reproduce, and two runs of the same
 * observation can land either side of a threshold. Deterministic metrics and comparison are
 * what a campaign should be made of; these are for the questions that have no right answer
 * to compare against.
 *
 * <p><b>Nothing to read is not a score.</b> Upstream scores 1 and passes when a trace carries
 * no plan, which is a check passing by finding nothing. Here a question that cannot be put
 * produces {@link RunOutcome.Inconclusive}, which keeps the run out of the pass rate and names
 * what was missing.
 *
 * <p>Ported in shape from DeepEval, Apache 2.0, read at commit bd10fa6. Upstream ships no
 * expected values for any of these, so no fixture here reproduces one &mdash; see
 * {@code PortedMetrics}.
 */
public abstract sealed class AlignmentMetric implements Scorer
    permits TaskCompletion, StepEfficiency, PlanQuality, PlanAdherence {

    /**
     * What one alignment question compares.
     *
     * @param task    what the agent was trying to do
     * @param against what it is being read against: the outcome, the plan, or the steps
     */
    public record Question(String label, String task, String against) {

        public Question {
            if (task == null || task.isBlank()) {
                throw new IllegalArgumentException("an alignment question needs a task");
            }
            if (against == null || against.isBlank()) {
                throw new IllegalArgumentException("an alignment question needs something to read against");
            }
        }
    }

    /**
     * Puts one alignment question to a model and returns what it said.
     *
     * <p>A function rather than an agent, so the scoring is testable with no provider and so
     * that {@code evalkit-core} keeps its empty dependency list. The reply is expected in the
     * shape {@link ModelReply} reads: a {@code SCORE} between 0 and 1, and a {@code REASON}.
     */
    @FunctionalInterface
    public interface Assessor {
        String assess(Question question);
    }

    private final MetricRef ref;
    private final double threshold;
    private final Assessor assessor;

    AlignmentMetric(MetricRef ref, double threshold, Assessor assessor) {
        this.ref = ref;
        this.threshold = threshold;
        this.assessor = assessor;
    }

    public MetricRef ref() {
        return ref;
    }

    public double threshold() {
        return threshold;
    }

    Assessor assessor() {
        return assessor;
    }

    /** The question this metric puts, or empty when the run recorded nothing to ask about. */
    protected abstract Optional<Question> ask(Observation observation);

    /** What was missing, for the row that says the run produced no evidence. */
    protected abstract String absence();

    @Override
    public String id() {
        return ref.label();
    }

    @Override
    public RunOutcome score(Observation observation) {
        Optional<Question> question = ask(observation);
        if (question.isEmpty()) {
            return new RunOutcome.Inconclusive(ref.metricId() + ": " + absence());
        }

        String reply = assessor.assess(question.orElseThrow());
        Optional<ModelReply.Stated> stated = ModelReply.read(reply);
        if (stated.isEmpty()) {
            return new RunOutcome.Inconclusive(
                ref.metricId() + ": the assessor returned no score");
        }

        Optional<Double> value = share(stated.orElseThrow().score());
        if (value.isEmpty()) {
            // A score outside 0 to 1 is a scale this metric cannot read, and reading it
            // anyway would put a number in the report that means something else.
            return new RunOutcome.Inconclusive(ref.metricId()
                + ": the assessor scored \"" + stated.orElseThrow().score() + "\", which is not a share");
        }

        double score = value.orElseThrow();
        return new RunOutcome.Measured(ref.metricId(), ref.version(), score, threshold,
            score >= threshold, stated.orElseThrow().explanation());
    }

    /** A score between 0 and 1, or empty when the text is not one. */
    static Optional<Double> share(String text) {
        try {
            double value = Double.parseDouble(text.strip());
            return value >= 0 && value <= 1 ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** The graded reply, falling back to the exchange when the target reported no final text. */
    static String outcomeOf(Observation observation) {
        var transcript = observation.transcript();
        return transcript.systemOutput().isBlank()
            ? transcript.simulationHistory() : transcript.systemOutput();
    }

    /**
     * The plan a run surfaced, taken from the reasoning its model calls carried.
     *
     * <p>An agent's plan is not in its reply. It is in the reasoning the provider returned
     * alongside it, which {@link io.akka.evalkit.domain.ModelCall#thinking()} records and
     * {@link io.akka.evalkit.domain.Evidence#thinking()} joins in call order.
     *
     * <p>Empty when no call reported reasoning, which is what a provider with reasoning
     * switched off produces. That is an absence of evidence about planning, not evidence
     * that the agent did not plan, and the metrics reading this report it as such.
     */
    static Optional<String> recordedPlan(Observation observation) {
        String reasoning = observation.thinking();
        return reasoning.isBlank() ? Optional.empty() : Optional.of(reasoning);
    }
}
