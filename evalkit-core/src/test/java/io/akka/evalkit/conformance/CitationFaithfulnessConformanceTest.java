package io.akka.evalkit.conformance;

import io.akka.evalkit.metric.CitationFaithfulness;
import io.akka.evalkit.metric.Finding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The passages, the scores and the misattribution case are DeepEval's.
 *
 * <p>Source: {@code tests/test_metrics/test_citation_faithfulness_metric.py} at commit
 * bd10fa6. Upstream states the point of the metric in its own docstring: a claim cited to
 * a passage that does not support it must fail, even when another passage would support
 * the same claim.
 */
@DisplayName("CitationFaithfulness · matches DeepEval's CitationFaithfulnessMetric")
class CitationFaithfulnessConformanceTest {

    private static final List<String> PASSAGES = List.of(
        "The Eiffel Tower stands 330 metres tall in Paris.",
        "The Eiffel Tower was completed in 1889 for the World Fair.");

    private final CitationFaithfulness metric = new CitationFaithfulness();

    @Test
    @DisplayName("a claim cited to the wrong passage scores 0.0")
    void misattributionFails() {
        // "The Eiffel Tower was completed in 1889 [1]." cites the height passage for a
        // year claim. Plain faithfulness passes this, because passage 2 supports the
        // claim. Attribution-aware checking fails it.
        var finding = Finding.denied("completed in 1889 [1]",
            "the year claim is cited to [1], which only covers height");

        assertThat(metric.aggregate(List.of(finding))).isEqualTo(0.0);
        assertThat(metric.withinThreshold(0.0)).isFalse();
    }

    @Test
    @DisplayName("every marker pointing at a supporting passage scores 1.0")
    void correctlyCitedPasses() {
        var finding = Finding.affirmed("330 metres tall [1] and completed in 1889 [2]");

        assertThat(metric.aggregate(List.of(finding))).isEqualTo(1.0);
        assertThat(metric.withinThreshold(1.0)).isTrue();
    }

    @Test
    @DisplayName("the passages are numbered from 1 so a marker resolves")
    void passagesAreNumbered() {
        // Upstream asserts both numbered lines appear in the prompt the judge receives.
        // A judge shown unnumbered passages answers about support instead of attribution,
        // which is the question this metric exists to ask.
        var numbered = CitationFaithfulness.numberPassages(PASSAGES);

        assertThat(numbered).contains("[1] The Eiffel Tower stands 330 metres tall");
        assertThat(numbered).contains("[2] The Eiffel Tower was completed in 1889");
        assertThat(numbered.lines()).hasSize(2);
    }

    @Test
    @DisplayName("no passages numbers nothing")
    void noPassages() {
        assertThat(CitationFaithfulness.numberPassages(List.of())).isEmpty();
    }

    @Test
    @DisplayName("one bad citation among two scores 0.5 and fails the default threshold")
    void oneBadCitationAmongTwo() {
        var findings = List.of(
            Finding.affirmed("330 metres tall [1]"),
            Finding.denied("completed in 1889 [1]", "cited to the height passage"));

        // The default threshold is 1.0: a reply is either correctly attributed or it
        // is not, and a half-attributed answer is one a reader cannot follow.
        assertThat(metric.aggregate(findings)).isEqualTo(0.5);
        assertThat(metric.withinThreshold(0.5)).isFalse();
    }
}
