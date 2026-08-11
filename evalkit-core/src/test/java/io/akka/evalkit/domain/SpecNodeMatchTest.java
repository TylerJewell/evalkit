package io.akka.evalkit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SpecNodeMatch · settling a decision without a model")
class SpecNodeMatchTest {

    @Test
    @DisplayName("the same node matches, whatever the casing")
    void exact() {
        assertThat(SpecNodeMatch.matches("GenUC-17a", "GenUC-17a")).isTrue();
        assertThat(SpecNodeMatch.matches("GLOB-11", "glob-11")).isTrue();
    }

    @Test
    @DisplayName("a more specific node satisfies a less specific expectation")
    void refinementSatisfies() {
        // Expecting GenUC-16a and landing on GenUC-16a.3 is landing in the right place.
        assertThat(SpecNodeMatch.matches("GenUC-16a", "GenUC-16a.3")).isTrue();
    }

    @Test
    @DisplayName("a less specific node does not satisfy a specific expectation")
    void generalisationDoesNot() {
        // Getting as far as GenUC-16a when GenUC-16a.3 was expected is stopping short.
        assertThat(SpecNodeMatch.matches("GenUC-16a.3", "GenUC-16a")).isFalse();
    }

    @Test
    @DisplayName("a refinement has to continue at a boundary, not mid-identifier")
    void notAPrefixTrap() {
        // GenUC-16ab is a different node, not a refinement of GenUC-16a. A plain
        // startsWith would have called this a pass.
        assertThat(SpecNodeMatch.matches("GenUC-16a", "GenUC-16ab")).isFalse();
        assertThat(SpecNodeMatch.matches("BAG-1", "BAG-11")).isFalse();
    }

    @Test
    @DisplayName("a different node is a mismatch, and both ids are reported")
    void mismatchIsInspectable() {
        var outcome = SpecNodeMatch.assertReached("GenUC-17a", Optional.of("GenUC-27"));

        assertThat(outcome).isInstanceOfSatisfying(RunOutcome.Asserted.class, a -> {
            assertThat(a.passed()).isFalse();
            assertThat(a.expected()).isEqualTo("GenUC-17a");
            assertThat(a.actual()).isEqualTo("GenUC-27");
        });
        assertThat(outcome.describe()).contains("expected GenUC-17a, found GenUC-27");
    }

    @Test
    @DisplayName("a reached node passes and needs no model")
    void reached() {
        var outcome = SpecNodeMatch.assertReached("GenUC-17a", Optional.of("GenUC-17a"));

        assertThat(outcome.passed()).isTrue();
        assertThat(outcome.isEvidence()).isTrue();
        assertThat(outcome).isInstanceOf(RunOutcome.Asserted.class);
    }

    @Test
    @DisplayName("a target that reports no node is unscoreable, not a pass and not a failure")
    void noNodeReported() {
        // Passing would make every assertion vacuous; failing would blame the system for
        // a harness that was never wired to read a node.
        var outcome = SpecNodeMatch.assertReached("GenUC-17a", Optional.empty());

        assertThat(outcome).isInstanceOfSatisfying(RunOutcome.Unscoreable.class,
            u -> assertThat(u.reason()).contains("reported none"));
        assertThat(outcome.isEvidence()).isFalse();
    }
}
