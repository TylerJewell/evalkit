package io.akka.evalkit.conformance;

import io.akka.evalkit.metric.Metric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A metric with no conformance fixture fails the build.
 *
 * <p>A ported metric that nobody checked looks the same from outside as one that matches
 * upstream exactly. This test reads what is on the classpath, reads what
 * {@link PortedMetrics} claims, and reports the difference in both directions.
 *
 * <p>The comparison itself is a pure function, and {@link #catchesAnUnregisteredMetric()}
 * proves it catches the case it exists for. A coverage check that can only pass by
 * finding nothing is the failure this repository has recorded twice.
 */
@DisplayName("Conformance coverage · every ported metric carries a fixture")
class ConformanceCoverageTest {

    /** Metric ids on the classpath that no entry claims. */
    static List<String> unregistered(Set<String> onClasspath, Set<String> registered) {
        return onClasspath.stream().filter(id -> !registered.contains(id)).sorted().toList();
    }

    /** Entries claiming a metric that is not on the classpath. */
    static List<String> orphaned(Set<String> onClasspath, Set<String> registered) {
        return registered.stream().filter(id -> !onClasspath.contains(id)).sorted().toList();
    }

    @Test
    @DisplayName("the comparison catches a metric nobody registered")
    void catchesAnUnregisteredMetric() {
        var onClasspath = Set.of("tool-permission", "turn-relevancy", "faithfulness");
        var registered = Set.of("tool-permission", "turn-relevancy");

        assertThat(unregistered(onClasspath, registered)).containsExactly("faithfulness");
        assertThat(orphaned(onClasspath, registered)).isEmpty();
    }

    @Test
    @DisplayName("the comparison catches an entry whose metric was deleted")
    void catchesAnOrphanedEntry() {
        var onClasspath = Set.of("tool-permission");
        var registered = Set.of("tool-permission", "turn-relevancy");

        assertThat(orphaned(onClasspath, registered)).containsExactly("turn-relevancy");
    }

    @Test
    @DisplayName("every metric on the classpath has an entry, and every entry has a metric")
    void classpathAndRegistryAgree() {
        var onClasspath = metricIdsOnClasspath();
        Set<String> registered = PortedMetrics.metrics().stream()
            .map(PortedMetrics.Entry::name)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        // A non-empty classpath is asserted first. An empty scan would make both
        // comparisons below pass while examining nothing.
        assertThat(onClasspath).isNotEmpty();
        assertThat(unregistered(onClasspath, registered))
            .as("metrics with no entry in PortedMetrics")
            .isEmpty();
        assertThat(orphaned(onClasspath, registered))
            .as("PortedMetrics entries naming a metric that no longer exists")
            .isEmpty();
    }

    @Test
    @DisplayName("every entry names a test class that exists")
    void everyEntryHasATestClass() {
        for (PortedMetrics.Entry entry : PortedMetrics.ENTRIES) {
            assertThat(classExists(entry.testClass()))
                .as("fixture for " + entry.name())
                .isTrue();
        }
    }

    @Test
    @DisplayName("every entry records the upstream file and the commit it was read at")
    void everyEntryRecordsItsProvenance() {
        for (PortedMetrics.Entry entry : PortedMetrics.ENTRIES) {
            // Without the commit, a maintainer cannot tell whether upstream has moved
            // since the expected values were copied.
            assertThat(entry.upstreamFile()).endsWith(".py");
            assertThat(entry.upstreamCommit()).hasSize(40);
        }
    }

    /**
     * Metric ids found by instantiating nothing and reading the package directory.
     *
     * <p>Reads {@code target/classes}, so this reports an empty set when the tests run
     * from a packaged jar. {@link #classpathAndRegistryAgree()} asserts the set is not
     * empty for that reason.
     */
    private static Set<String> metricIdsOnClasspath() {
        var url = Metric.class.getClassLoader().getResource("io/akka/evalkit/metric");
        if (url == null || !"file".equals(url.getProtocol())) return Set.of();
        try (Stream<Path> files = Files.list(Path.of(url.toURI()))) {
            return files
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".class") && !name.contains("$"))
                .map(name -> "io.akka.evalkit.metric." + name.substring(0, name.length() - 6))
                .map(ConformanceCoverageTest::load)
                .filter(type -> Metric.class.isAssignableFrom(type)
                    && !type.isInterface()
                    && !Modifier.isAbstract(type.getModifiers()))
                .map(ConformanceCoverageTest::metricIdOf)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("could not read the metric package", e);
        }
    }

    /**
     * The id a metric stamps onto its scores, taken from the class name.
     *
     * <p>Reading {@code ref()} would need an instance, and every metric's constructor
     * takes different arguments. The convention is that {@code ToolPermission} carries
     * the id {@code tool-permission}, and a metric that breaks it fails this test.
     */
    private static String metricIdOf(Class<?> type) {
        return type.getSimpleName()
            .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
            .toLowerCase(java.util.Locale.ROOT);
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("compiled class missing: " + name, e);
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
