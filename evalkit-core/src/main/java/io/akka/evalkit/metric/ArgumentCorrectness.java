package io.akka.evalkit.metric;

import io.akka.evalkit.domain.RunOutcome;

import java.util.List;

/**
 * The share of an agent's tool calls that were made with the right arguments.
 *
 * <p>Judges arguments, where {@link ToolCorrectness} judges which tools were called. This
 * one names no expected tools at all: a model reads what the user asked for and decides
 * whether each call was made with arguments that serve it, so a scenario carries no list of
 * arguments to keep up to date.
 *
 * <p>One judgement per tool call, and the arithmetic is the share that went the metric's way
 * &mdash; the same split {@link TurnFaithfulness} uses, for the same reason: collecting the
 * judgements needs a model and turning them into a number does not.
 *
 * <p>Ported from DeepEval's {@code ArgumentCorrectnessMetric}, Apache 2.0, read at commit
 * bd10fa6. <b>One divergence.</b> Upstream scores a run with no tool calls 1 and passes it.
 * A metric about tool arguments that scores full marks because no tool was called is a check
 * passing by finding nothing, so a run with nothing to judge is
 * {@link RunOutcome.Unscoreable} here and stays out of the pass rate. The arithmetic below is
 * still upstream's, including what it returns for an empty list; the divergence is in what
 * the run is reported as, not in what the formula computes.
 */
public final class ArgumentCorrectness implements Metric {

    private final MetricRef ref;
    private final double threshold;

    public ArgumentCorrectness() {
        this(new MetricRef("argument-correctness", 1), 0.5);
    }

    private ArgumentCorrectness(MetricRef ref, double threshold) {
        this.ref = ref;
        this.threshold = threshold;
    }

    public ArgumentCorrectness scoringAtLeast(double newThreshold) {
        return new ArgumentCorrectness(new MetricRef(ref.metricId(), ref.version() + 1), newThreshold);
    }

    @Override
    public MetricRef ref() {
        return ref;
    }

    @Override
    public double threshold() {
        return threshold;
    }

    /** Calls made with the right arguments over calls made. */
    @Override
    public double aggregate(List<Judgement> judgements) {
        return Metric.shareAffirmed(judgements);
    }

    /**
     * The score as an outcome, or an admission that there was nothing to score.
     *
     * <p>See the divergence on this class. A campaign of these against an agent that stopped
     * calling tools would otherwise report a rising pass rate as the agent did less.
     */
    @Override
    public RunOutcome outcome(List<Judgement> judgements) {
        if (judgements.isEmpty()) {
            return new RunOutcome.Unscoreable(
                "argument-correctness: the run made no tool call to judge arguments on");
        }
        return Metric.super.outcome(judgements);
    }
}
