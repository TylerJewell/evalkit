package io.akka.evalkit.metric;

import akka.javasdk.ledger.ToolCall;
import io.akka.evalkit.ledger.Arguments;
import io.akka.evalkit.ledger.Interactions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Whether an agent called the tools a scenario expected it to call.
 *
 * <p>Deterministic. The judgements come from matching what was called against what was
 * expected, and the score comes from the credit those matches carry, so a campaign of these
 * costs nothing and returns the same answer on every run.
 *
 * <p><b>The denominator is what was expected, not what was called.</b> An agent that called
 * six tools when two were expected and got both right scores 1. The metric asks whether the
 * work was done, and {@link ToolPermission} is what asks whether anything else was done
 * besides.
 *
 * <p>Three modes, and they do not combine: {@link #exactly()} demands the same tools in the
 * same positions, {@link #inOrder()} credits the longest run that kept the expected
 * sequence, and the default credits a match wherever it appears. {@link #comparingArguments()}
 * adds the arguments to whichever of the three is in force.
 *
 * <p>Ported from DeepEval's {@code ToolCorrectnessMetric}, Apache 2.0, read at commit
 * bd10fa6. One thing there has no counterpart here: upstream can take the lower of this
 * score and a model's opinion of whether better tools were available, which belongs to
 * {@code TaskCompletion} in this kit rather than to a deterministic comparison.
 */
public final class ToolCorrectness implements Metric {

    /** How a called tool is matched against an expected one. */
    private enum Match {
        /** Same tools, same positions. Any difference scores zero. */
        EXACT,
        /** The longest run of expected tools that were called in the expected order. */
        ORDERED,
        /** A match anywhere, which is what upstream does when asked for neither. */
        ANYWHERE
    }

    private final MetricRef ref;
    private final List<ToolCall> expected;
    private final double threshold;
    private final Match match;
    private final boolean comparingArguments;
    private final boolean comparingOutput;
    private final boolean strict;

    private ToolCorrectness(MetricRef ref, List<ToolCall> expected, double threshold,
                            Match match, boolean comparingArguments, boolean comparingOutput,
                            boolean strict) {
        this.ref = ref;
        this.expected = List.copyOf(expected);
        this.threshold = threshold;
        this.match = match;
        this.comparingArguments = comparingArguments;
        this.comparingOutput = comparingOutput;
        this.strict = strict;
    }

    /** The tools this scenario expects, by name. */
    public static ToolCorrectness expecting(String... tools) {
        return expecting(java.util.Arrays.stream(tools).map(Interactions::tool).toList());
    }

    /** The tools this scenario expects, with the arguments {@link #comparingArguments()} reads. */
    public static ToolCorrectness expecting(List<ToolCall> tools) {
        return new ToolCorrectness(new MetricRef("tool-correctness", 1), tools, 0.5,
            Match.ANYWHERE, false, false, false);
    }

    /** The same tools in the same positions, or nothing. */
    public ToolCorrectness exactly() {
        return new ToolCorrectness(ref, expected, threshold, Match.EXACT, comparingArguments,
            comparingOutput, strict);
    }

    /** Credit the expected tools that were called in the expected order. */
    public ToolCorrectness inOrder() {
        return new ToolCorrectness(ref, expected, threshold, Match.ORDERED, comparingArguments,
            comparingOutput, strict);
    }

    /** Compare the arguments as well as the names. */
    public ToolCorrectness comparingArguments() {
        return new ToolCorrectness(ref, expected, threshold, match, true, comparingOutput, strict);
    }

    /**
     * Compare what the tool returned as well as what it was called with.
     *
     * <p>All or nothing per call, as upstream has it: a response that differs from the
     * expected one scores that call zero whatever its arguments did.
     *
     * <p><b>Only useful against a target that records tool responses.</b>
     * {@link ToolCall#response()} is empty when the target reported the call and not its
     * return, and an empty response does not equal an expected one, so a campaign that
     * enables this against a target that records nothing scores zero throughout.
     */
    public ToolCorrectness comparingOutput() {
        return new ToolCorrectness(ref, expected, threshold, match, comparingArguments, true, strict);
    }

    /** The score a run must reach. Changing it changes the version. */
    public ToolCorrectness scoringAtLeast(double newThreshold) {
        return new ToolCorrectness(new MetricRef(ref.metricId(), ref.version() + 1), expected,
            newThreshold, match, comparingArguments, comparingOutput, strict);
    }

    /** Anything short of the threshold scores zero rather than a fraction. */
    public ToolCorrectness strict() {
        return new ToolCorrectness(ref, expected, 1.0, match, comparingArguments,
            comparingOutput, true);
    }

    @Override
    public MetricRef ref() {
        return ref;
    }

    @Override
    public double threshold() {
        return threshold;
    }

    /**
     * One judgement per expected tool, naming the tool that was expected.
     *
     * <p>A campaign that expected nothing and got a call produces the single judgement
     * below instead, because there is no expected tool to hang it on and a run with no
     * judgements is a run that scores 1.
     */
    public List<Judgement> judge(List<ToolCall> called) {
        if (expected.isEmpty()) {
            return called.isEmpty() ? List.of()
                : List.of(Judgement.denied("no tool expected",
                    "called " + names(called) + " when the scenario expected none"));
        }
        return switch (match) {
            case EXACT -> judgeExactly(called);
            case ORDERED -> judgeInOrder(called);
            case ANYWHERE -> judgeAnywhere(called);
        };
    }

    private List<Judgement> judgeExactly(List<ToolCall> called) {
        if (called.size() != expected.size()) {
            return List.of(Judgement.denied("tool sequence",
                "expected " + names(expected) + ", called " + names(called)));
        }
        var judgements = new ArrayList<Judgement>();
        for (int i = 0; i < expected.size(); i++) {
            ToolCall want = expected.get(i);
            ToolCall got = called.get(i);
            // Parsed on both sides, because two calls carrying the same members in a
            // different order spell different JSON and agree on every argument.
            boolean same = want.name().equals(got.name())
                && (!comparingArguments
                    || Arguments.parse(want.arguments()).equals(Arguments.parse(got.arguments())))
                && (!comparingOutput || want.response().equals(got.response()));
            judgements.add(same ? Judgement.affirmed(want.name())
                : Judgement.denied(want.name(), "position " + (i + 1) + " called " + got.name()));
        }
        return judgements;
    }

    /**
     * The best available match for each expected tool, each called tool spent once.
     *
     * <p>Spending a called tool stops one call satisfying two expectations, which is what
     * upstream does by removing a matched call from the pool.
     */
    private List<Judgement> judgeAnywhere(List<ToolCall> called) {
        var spent = new HashSet<Integer>();
        var judgements = new ArrayList<Judgement>();
        for (ToolCall want : expected) {
            double best = 0;
            int bestAt = -1;
            for (int i = 0; i < called.size(); i++) {
                if (spent.contains(i) || !want.name().equals(called.get(i).name())) continue;
                double credit = creditFor(want, called.get(i));
                if (credit > best) {
                    best = credit;
                    bestAt = i;
                }
            }
            if (bestAt >= 0) spent.add(bestAt);
            judgements.add(judgement(want, best, called));
        }
        return judgements;
    }

    /**
     * The longest subsequence of expected tools that were called in order.
     *
     * <p>The table is upstream's, and the credit each expected tool carries is what it added
     * to the total when the trace walked back through it. A tool that was called but out of
     * sequence adds nothing, which is the difference between this mode and the one above.
     */
    private List<Judgement> judgeInOrder(List<ToolCall> called) {
        int rows = expected.size();
        int columns = called.size();
        var best = new double[rows + 1][columns + 1];
        for (int i = 1; i <= rows; i++) {
            for (int j = 1; j <= columns; j++) {
                double credit = expected.get(i - 1).name().equals(called.get(j - 1).name())
                    ? creditFor(expected.get(i - 1), called.get(j - 1)) : 0;
                best[i][j] = Math.max(best[i - 1][j], best[i][j - 1]);
                if (credit > 0) {
                    best[i][j] = Math.max(best[i][j], best[i - 1][j - 1] + credit);
                }
            }
        }

        var earned = new double[rows];
        int i = rows;
        int j = columns;
        while (i > 0 && j > 0) {
            if (best[i][j] == best[i - 1][j]) {
                i--;
            } else if (best[i][j] == best[i][j - 1]) {
                j--;
            } else {
                earned[i - 1] = best[i][j] - best[i - 1][j - 1];
                i--;
                j--;
            }
        }

        var judgements = new ArrayList<Judgement>();
        for (int at = 0; at < rows; at++) {
            judgements.add(judgement(expected.get(at), earned[at], called));
        }
        return judgements;
    }

    private Judgement judgement(ToolCall want, double credit, List<ToolCall> called) {
        if (credit >= 1.0) return Judgement.affirmed(want.name());
        if (credit > 0) {
            return Judgement.partial(want.name(), credit,
                "called with different arguments than expected");
        }
        return Judgement.denied(want.name(), "not among " + names(called));
    }

    /** How much one call satisfies one expectation, once the names already agree. */
    private double creditFor(ToolCall want, ToolCall got) {
        // A wrong return is a wrong call whatever the arguments were, so this is checked
        // before the arguments earn any partial credit.
        if (comparingOutput && !want.response().equals(got.response())) return 0.0;
        return comparingArguments
            ? compare(Arguments.parse(want.arguments()), Arguments.parse(got.arguments()))
            : 1.0;
    }

    /**
     * How closely two argument maps agree.
     *
     * <p>The share of the keys either side names that both sides name with the same value.
     * A call carrying three of four expected arguments is closer to right than one carrying
     * none, and a metric that reports both as zero cannot say so.
     */
    private double compare(Map<String, String> want, Map<String, String> got) {
        if (want.equals(got)) return 1.0;
        if (match == Match.EXACT) return 0.0;

        var union = new HashSet<>(want.keySet());
        union.addAll(got.keySet());
        if (union.isEmpty()) return 1.0;

        long agreed = want.keySet().stream()
            .filter(key -> got.containsKey(key) && Objects.equals(want.get(key), got.get(key)))
            .count();
        return (double) agreed / union.size();
    }

    /**
     * The credit earned over the tools expected.
     *
     * <p>Pure, and the empty list scores 1 for the reason {@link Metric#shareAffirmed}
     * gives: a scenario that expected no tools and saw none called found nothing wrong.
     */
    @Override
    public double aggregate(List<Judgement> judgements) {
        double score = raw(judgements);
        return strict && score < threshold ? 0.0 : score;
    }

    private double raw(List<Judgement> judgements) {
        if (judgements.isEmpty()) return 1.0;
        if (match == Match.EXACT) {
            // All or nothing. A sequence that got three of four positions right is not
            // three quarters of an exact match; it is not an exact match.
            return judgements.stream().allMatch(Judgement::affirmed) ? 1.0 : 0.0;
        }
        return judgements.stream().mapToDouble(Judgement::credit).sum() / judgements.size();
    }

    /** The expected tools a run did not call, for the report row. */
    public static List<String> missing(List<Judgement> judgements) {
        return judgements.stream()
            .filter(judgement -> judgement.credit() == 0)
            .map(Judgement::subject)
            .toList();
    }

    private static String names(List<ToolCall> tools) {
        Set<String> named = new java.util.LinkedHashSet<>(tools.stream().map(ToolCall::name).toList());
        return named.toString();
    }
}
