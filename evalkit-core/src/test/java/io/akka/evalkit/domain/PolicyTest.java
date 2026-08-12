package io.akka.evalkit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Policy · the rules the system was measured against")
class PolicyTest {

    private static final Policy V1 = new Policy("refund-desk", 1, "Refunds within 30 days.");
    private static final Policy V2 = new Policy("refund-desk", 2, "Refunds within 14 days.");

    private static final Rubric RUBRIC = Rubric.load("scenario-judge", 3);

    private static Grade verdict(String scenario, int score) {
        return Grade.of(scenario, RUBRIC, score, "because");
    }

    @Test
    @DisplayName("two runs under different policy versions cannot be compared")
    void refusesAcrossPolicyVersions() {
        // The window changed from 30 days to 14. Every scenario written against 30 now
        // fails, and the drop reads as a service regression rather than a rule change.
        var baseline = List.of(verdict("refund-30d", 9));
        var candidate = List.of(verdict("refund-30d", 2));

        assertThatThrownBy(() -> Scoring.compare(V1, baseline, V2, candidate))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("refund-desk v1")
            .hasMessageContaining("refund-desk v2")
            .hasMessageContaining("told different things");
    }

    @Test
    @DisplayName("two runs under the same policy compare normally")
    void allowsTheSamePolicy() {
        var comparison = Scoring.compare(V1, List.of(verdict("refund-30d", 9)),
            V1, List.of(verdict("refund-30d", 2)));

        assertThat(comparison.regressed()).contains("refund-30d");
    }

    @Test
    @DisplayName("a policy with no text is refused, because its label would still gate comparison")
    void textIsRequired() {
        assertThatThrownBy(() -> new Policy("refund-desk", 1, "  "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no text");
        assertThatThrownBy(() -> new Policy("refund-desk", 0, "rules"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("version starts at 1");
    }

    @Test
    @DisplayName("a campaign records the policy it ran under")
    void planCarriesThePolicy() {
        var scenario = new Scenario("refund-30d", Optional.of("GenUC-1"),
            new Precursor.None(), "what if they don't fit?", "a 30-day refund");
        var plan = new CampaignPlan("refunds", List.of(scenario), Lanes.of(1),
            RUBRIC);

        assertThat(plan.policy()).isEmpty();
        assertThat(plan.under(V1).policy()).contains(V1);
    }

    @Test
    @DisplayName("the opening states the policy, and says why it matters")
    void openingNamesThePolicy() {
        var text = openingUnder(Optional.of(V1)).replaceAll("\\s+", " ");

        assertThat(text).contains("policy refund-desk v1");
        assertThat(text).contains("comparable with another run under the same policy");
    }

    @Test
    @DisplayName("a campaign stating no policy prints no claim about one")
    void openingOmitsAnAbsentPolicy() {
        assertThat(openingUnder(Optional.empty())).doesNotContain("policy");
    }

    private static String openingUnder(Optional<Policy> policy) {
        var coverage = new RunSummary.Coverage(
            List.of(new RunSummary.Journey("refunds", 40)), List.of());
        var judge = new RunSummary.JudgeProfile("scenario-judge", 3, "28 July",
            "target/eval/rubric.txt", 89, 91, 53, "Those 4 are enquiries.");
        return RunSummary.opening(
            new RunSummary.Identity("Refunds", "R-1", "svc 1.0"), coverage, 36, 4, judge,
            java.util.Map.of("fresh", "nothing configured"), java.util.Map.of("fresh", 40),
            "scenarios.jsonl", null, policy);
    }
}
