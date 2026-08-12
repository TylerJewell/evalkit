package io.akka.evalkit.conformance;

import io.akka.evalkit.metric.Finding;
import io.akka.evalkit.metric.TurnRelevancy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The score table is copied from DeepEval's own parameterised test.
 *
 * <p>Source: {@code tests/test_metrics/test_turn_relevancy_aggregation.py} at commit
 * bd10fa6. Upstream scripts a fake judge that returns prepared verdicts in order and
 * asserts the score that follows. The findings here are built directly, which tests the
 * same arithmetic without the scripted model in between.
 */
@DisplayName("TurnRelevancy · matches DeepEval's TurnRelevancyMetric arithmetic")
class TurnRelevancyConformanceTest {

    private final TurnRelevancy metric = new TurnRelevancy();

    private static List<Finding> findings(int irrelevant, int relevant) {
        var out = new ArrayList<Finding>();
        for (int i = 0; i < irrelevant; i++) {
            out.add(Finding.denied("exchange " + out.size(), "scripted irrelevancy"));
        }
        for (int i = 0; i < relevant; i++) {
            out.add(Finding.affirmed("exchange " + out.size()));
        }
        return out;
    }

    @ParameterizedTest(name = "{0} exchanges with {1} irrelevant scores {2}")
    @CsvSource({
        "10, 1, 0.9",
        "10, 2, 0.8",
        "20, 1, 0.95",
        " 4, 4, 0.0",
        " 4, 0, 1.0"
    })
    @DisplayName("the score is the share of exchanges judged relevant")
    void scoreIsTheShareOfRelevantExchanges(int exchanges, int irrelevant, double expected) {
        var scripted = findings(irrelevant, exchanges - irrelevant);

        assertThat(metric.aggregate(scripted)).isEqualTo(expected);
        assertThat(scripted).hasSize(exchanges);
    }

    @Test
    @DisplayName("no readable finding scores 1, which is the documented fallback")
    void noJudgementsScoresOne() {
        // Upstream returns 1 when every verdict was unreadable or absent. Porting the
        // metric without the fallback would report a service as irrelevant on the
        // strength of a judge that never answered.
        assertThat(metric.aggregate(List.of())).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a single irrelevant exchange scores 0")
    void oneIrrelevantExchange() {
        assertThat(metric.aggregate(findings(1, 0))).isEqualTo(0.0);
    }

    @Test
    @DisplayName("half relevant scores 0.5")
    void halfRelevant() {
        assertThat(metric.aggregate(findings(1, 1))).isEqualTo(0.5);
    }

    @Test
    @DisplayName("the order findings arrive in does not change the score")
    void orderDoesNotMatter() {
        var denialFirst = List.of(Finding.denied("a", "no"), Finding.affirmed("b"));
        var denialLast = List.of(Finding.affirmed("b"), Finding.denied("a", "no"));

        assertThat(metric.aggregate(denialFirst)).isEqualTo(metric.aggregate(denialLast));
    }
}
