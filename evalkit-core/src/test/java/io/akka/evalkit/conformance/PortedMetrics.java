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
     * @param name           the metric id, or the type name for a component
     * @param kind           whether the classpath scan is expected to find it
     * @param upstreamClass  the class the values were taken from
     * @param upstreamFile   path within the upstream repository
     * @param upstreamCommit the commit read, so the values can be fetched again
     * @param testClass      the fixture that pins the behaviour
     */
    record Entry(String name, Kind kind, String upstreamClass, String upstreamFile,
                 String upstreamCommit, String testClass) {}

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
            "io.akka.evalkit.conformance.DagConformanceTest"));

    static List<Entry> metrics() {
        return ENTRIES.stream().filter(entry -> entry.kind() == Kind.METRIC).toList();
    }

    private PortedMetrics() {}
}
