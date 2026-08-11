package io.akka.evalkit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Scoring · bands, distribution and honest comparison")
class ScoringTest {

    private static final Rubric V2 = new Rubric("scenario-judge", 2, template());
    private static final Rubric V3 = new Rubric("scenario-judge", 3, template());

    private static String template() {
        return "{replay_history}{simulation_history}{system_output}{expected_outcome}";
    }

    private static Verdict verdict(String scenario, int score, Rubric rubric) {
        return Verdict.of(scenario, rubric, score, "");
    }

    // ---- bands ----

    @Test
    @DisplayName("the rubric's three bands, at their boundaries")
    void bands() {
        assertThat(Band.of(1)).isEqualTo(Band.NO_MATCH);
        assertThat(Band.of(3)).isEqualTo(Band.NO_MATCH);
        assertThat(Band.of(4)).isEqualTo(Band.PARTIAL);
        assertThat(Band.of(7)).isEqualTo(Band.PARTIAL);
        assertThat(Band.of(8)).isEqualTo(Band.FAITHFUL);
        assertThat(Band.of(10)).isEqualTo(Band.FAITHFUL);
    }

    @Test
    @DisplayName("only a faithful match passes")
    void onlyFaithfulPasses() {
        // 7 is "somewhat matches". Counting that as a pass would report a half-right
        // answer about somebody's money as a success.
        assertThat(Band.of(7).passed()).isFalse();
        assertThat(Band.of(8).passed()).isTrue();
    }

    @Test
    @DisplayName("a score off the scale is refused, not clamped")
    void offScale() {
        assertThatThrownBy(() -> Band.of(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Band.of(11)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- parsing what a model actually says ----

    @Test
    @DisplayName("the score is read out of the replies models really give")
    void parsing() {
        assertThat(Verdict.parseScore("8")).contains(8);
        assertThat(Verdict.parseScore("Score: 8")).contains(8);
        assertThat(Verdict.parseScore("8/10")).contains(8);
        assertThat(Verdict.parseScore("10")).contains(10);
    }

    @Test
    @DisplayName("an unreadable reply yields nothing rather than a default")
    void unparseable() {
        // A default here is a fabricated judgement that shifts a band distribution.
        assertThat(Verdict.parseScore("I cannot assess this")).isEmpty();
        assertThat(Verdict.parseScore("")).isEmpty();
        assertThat(Verdict.parseScore(null)).isEmpty();
    }

    // ---- distribution ----

    @Test
    @DisplayName("a distribution counts bands, not averages")
    void distribution() {
        var d = Scoring.distribution(List.of(
            verdict("a", 9, V2), verdict("b", 10, V2), verdict("c", 5, V2), verdict("d", 2, V2)));

        assertThat(d.total()).isEqualTo(4);
        assertThat(d.count(Band.FAITHFUL)).isEqualTo(2);
        assertThat(d.count(Band.PARTIAL)).isEqualTo(1);
        assertThat(d.count(Band.NO_MATCH)).isEqualTo(1);
        assertThat(d.share(Band.FAITHFUL)).isEqualTo(0.5);
        assertThat(d.passed()).isEqualTo(2);
    }

    // ---- comparison ----

    @Test
    @DisplayName("movement is reported by band, in both directions")
    void comparison() {
        var baseline = List.of(verdict("a", 2, V2), verdict("b", 9, V2), verdict("c", 5, V2));
        var candidate = List.of(verdict("a", 9, V2), verdict("b", 3, V2), verdict("c", 6, V2));

        var c = Scoring.compare(baseline, candidate);

        assertThat(c.improved()).containsExactly("a");
        assertThat(c.regressed()).containsExactly("b");
        // 5 -> 6 is still PARTIAL. A mean would have called that an improvement.
        assertThat(c.unchanged()).containsExactly("c");
        assertThat(c.isRegression()).isTrue();
    }

    @Test
    @DisplayName("scenarios on one side only are not evidence either way")
    void asymmetric() {
        var c = Scoring.compare(List.of(verdict("a", 9, V2)), List.of(verdict("b", 9, V2)));

        assertThat(c.onlyIn()).containsExactly("a", "b");
        assertThat(c.improved()).isEmpty();
        assertThat(c.regressed()).isEmpty();
    }

    @Test
    @DisplayName("comparing across rubric versions is refused")
    void differentRubricsRefused() {
        // The failure this prevents: scoring the baseline on v2 and the candidate on v3,
        // then attributing the movement to the system rather than to the ruler.
        assertThatThrownBy(() ->
            Scoring.compare(List.of(verdict("a", 5, V2)), List.of(verdict("a", 9, V3))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("re-score the baseline");
    }

    @Test
    @DisplayName("repeated runs must be aggregated before comparing, not silently collapsed")
    void repeatedRuns() {
        // The reference dataset ran scenarios 1 to 61 times. Letting the last one win
        // would make a comparison depend on iteration order.
        assertThatThrownBy(() ->
            Scoring.compare(List.of(verdict("a", 9, V2), verdict("a", 2, V2)),
                            List.of(verdict("a", 9, V2))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("more than one verdict");
    }
}
