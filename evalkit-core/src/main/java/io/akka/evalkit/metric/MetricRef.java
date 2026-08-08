package io.akka.evalkit.metric;

/**
 * Which metric produced a score, and under which threshold and formula.
 *
 * <p>A metric's score depends on numbers a person chose. Raising a similarity threshold
 * from 0.75 to 0.80 turns passing runs into failing ones with no change to the service
 * under test, and a report that records only the score cannot tell the two apart six
 * weeks later.
 *
 * <p>The version therefore changes whenever the threshold or the arithmetic changes, and
 * comparison across versions is refused for the same reason it is refused across rubric
 * versions.
 */
public record MetricRef(String metricId, int version) {

    public MetricRef {
        if (metricId == null || metricId.isBlank()) {
            throw new IllegalArgumentException("metric id required");
        }
        if (version < 1) throw new IllegalArgumentException("metric version starts at 1");
    }

    /** {@code tool-permission v1}, for a report row and for a fixture name. */
    public String label() {
        return metricId + " v" + version;
    }
}
