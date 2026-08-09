package io.akka.evalkit.conformance;

import java.util.List;

/**
 * Which parts of this kit claim to match an upstream implementation, and where the claim
 * came from.
 *
 * <p>A ported metric with no fixture behind it is a metric nobody has checked. The entries
 * here name the upstream file and commit that supplied the expected values, so a reader
 * can fetch the source and a maintainer can re-sync when the upstream prompt changes.
 *
 * <p>{@link ConformanceCoverageTest} fails when a metric on the classpath has no entry
 * here, and when an entry has no test class.
 */
final class PortedMetrics {

    /** Whether an entry names a {@code Metric} or a component the scan cannot see. */
    enum Kind {
        /** Implements {@code Metric}, so the classpath scan finds it and checks it. */
        METRIC,
        /** A supporting type such as the decision graph, which carries no metric id. */
        COMPONENT
    }

    /**
     * What the fixture behind an entry is worth.
     *
     * <p>Two different claims, and a reader of a report is entitled to know which one is
     * being made about a score.
     */
    enum Pinning {
        /**
         * Upstream ships expected values and the fixture reproduces them. A change upstream
         * that moves a score breaks this build.
         */
        PINNED,
        /**
         * Upstream ships no expected values, so there is nothing to reproduce. The fixture
         * still fixes this kit's behaviour, and the entry says why nothing stronger is
         * available. This is not a suppression: the absence is upstream's, and no work here
         * would remove the entry.
         */
        UNPINNED
    }

    /**
     * @param name           the metric id, or the type name for a component
     * @param kind           whether the classpath scan is expected to find it
     * @param pinning        whether upstream supplied the expected values
     * @param upstreamClass  the class the behaviour was taken from
     * @param upstreamFile   path within the upstream repository
     * @param upstreamCommit the commit read, so the source can be fetched again
     * @param testClass      the fixture that holds the behaviour
     * @param note           why no upstream values exist, required of an unpinned entry and
     *                       empty on a pinned one
     */
    record Entry(String name, Kind kind, Pinning pinning, String upstreamClass,
                 String upstreamFile, String upstreamCommit, String testClass, String note) {

        /** A pinned entry, which is every port made against upstream's own expected values. */
        Entry(String name, Kind kind, String upstreamClass, String upstreamFile,
              String upstreamCommit, String testClass) {
            this(name, kind, Pinning.PINNED, upstreamClass, upstreamFile, upstreamCommit,
                testClass, "");
        }
    }

    static final String UPSTREAM = "confident-ai/deepeval, Apache 2.0";
    private static final String COMMIT = "bd10fa61c1727996607593d26631f9b22bb41d14";

    static final List<Entry> ENTRIES = List.of(

        new Entry("tool-permission", Kind.METRIC, "ToolPermissionMetric",
            "tests/test_metrics/test_tool_permission_metric.py", COMMIT,
            "io.akka.evalkit.conformance.ToolPermissionConformanceTest"),

        new Entry("turn-relevancy", Kind.METRIC, "TurnRelevancyMetric",
            "tests/test_metrics/test_turn_relevancy_aggregation.py", COMMIT,
            "io.akka.evalkit.conformance.TurnRelevancyConformanceTest"),

        new Entry("turn-faithfulness", Kind.METRIC, "TurnFaithfulnessMetric",
            "tests/test_metrics/test_turn_faithfulness_metric_empty_verdicts.py", COMMIT,
            "io.akka.evalkit.conformance.TurnFaithfulnessConformanceTest"),

        new Entry("citation-faithfulness", Kind.METRIC, "CitationFaithfulnessMetric",
            "tests/test_metrics/test_citation_faithfulness_metric.py", COMMIT,
            "io.akka.evalkit.conformance.CitationFaithfulnessConformanceTest"),

        new Entry("Dag", Kind.COMPONENT, "DAGMetric",
            "tests/test_metrics/test_dag.py", COMMIT,
            "io.akka.evalkit.conformance.DagConformanceTest"),

        new Entry("tool-correctness", Kind.METRIC, Pinning.UNPINNED, "ToolCorrectnessMetric",
            "deepeval/metrics/tool_correctness/tool_correctness.py", COMMIT,
            "io.akka.evalkit.conformance.ToolCorrectnessConformanceTest",
            "upstream ships no test for this metric, so the cases below are read off the "
                + "implementation rather than reproduced from expected values"),

        new Entry("argument-correctness", Kind.METRIC, Pinning.UNPINNED,
            "ArgumentCorrectnessMetric",
            "deepeval/metrics/argument_correctness/argument_correctness.py", COMMIT,
            "io.akka.evalkit.conformance.ArgumentCorrectnessConformanceTest",
            "upstream ships no test for this metric, so the arithmetic below is read off "
                + "_calculate_score rather than reproduced from expected values"),

        alignment("task-completion", "TaskCompletionMetric",
            "deepeval/metrics/task_completion/task_completion.py"),

        alignment("step-efficiency", "StepEfficiencyMetric",
            "deepeval/metrics/step_efficiency/step_efficiency.py"),

        alignment("plan-quality", "PlanQualityMetric",
            "deepeval/metrics/plan_quality/plan_quality.py"),

        alignment("plan-adherence", "PlanAdherenceMetric",
            "deepeval/metrics/plan_adherence/plan_adherence.py"));

    /**
     * One of the trace-level metrics whose score is a model's opinion.
     *
     * <p>Upstream's tests for these four skip without an API key, run a live agent and assert
     * nothing about the score, so there is no value to reproduce. What the fixture holds is
     * this kit's own behaviour around the call: the shape it reads, the scale it accepts, and
     * what it reports when there is nothing to ask about.
     */
    private static Entry alignment(String name, String upstreamClass, String upstreamFile) {
        return new Entry(name, Kind.METRIC, Pinning.UNPINNED, upstreamClass, upstreamFile,
            COMMIT, "io.akka.evalkit.conformance.AlignmentMetricConformanceTest",
            "upstream's test for this metric asserts nothing about the score, so there are "
                + "no expected values to reproduce");
    }

    static List<Entry> metrics() {
        return ENTRIES.stream().filter(entry -> entry.kind() == Kind.METRIC).toList();
    }

    /**
     * Unpinned entries that do not say why, which is an entry claiming less than it should.
     *
     * <p>A pure function over the list so that {@code ConformanceCoverageTest} can prove it
     * catches the case before applying it to the real entries.
     */
    static List<String> unexplained(List<Entry> entries) {
        return entries.stream()
            .filter(entry -> entry.pinning() == Pinning.UNPINNED)
            .filter(entry -> entry.note().isBlank())
            .map(Entry::name)
            .toList();
    }

    private PortedMetrics() {}
}
