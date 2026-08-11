package io.akka.evalkit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("A tool made to fail, so recovery is tested rather than assumed")
class FailingToolTest {

    private static final Rubric RUBRIC = Rubric.load("scenario-judge", 3);

    /** A target that can break the tools it names, and answers differently when one is broken. */
    private record Recovering(Set<String> breakable, boolean fabricates)
        implements SystemUnderTest {

        @Override
        public Prepared prepare(Precursor precursor) {
            return new Prepared.Ready("s1", "");
        }

        @Override
        public Reply submit(String sessionId, String userText) {
            String text = fabricates
                ? "Your balance is $421.00."
                : "I can't reach the account service right now. I've raised a ticket.";
            return new Reply(text, Optional.of("GenUC-9"), Optional.empty(), List.of());
        }

        @Override
        public Map<String, String> fixtures() {
            return Map.of();
        }

        @Override
        public Set<String> breakableTools() {
            return breakable;
        }
    }

    private static Scenario recoveryScenario() {
        return new Scenario("balance-when-down", Optional.empty(), new Precursor.None(),
            "what is my balance?", "says it cannot check and offers a next step")
            .requiring("can't reach", "ticket");
    }

    private static Scenario withBrokenTool() {
        return new Scenario("balance-when-down", Optional.empty(), Optional.empty(),
            List.of("can't reach", "ticket"),
            Precursor.FailingTool.named("lookup_account"),
            "what is my balance?", "says it cannot check and offers a next step");
    }

    @Test
    @DisplayName("the precursor names the tool it breaks, and says so in the report")
    void namesTheTool() {
        var precursor = Precursor.FailingTool.named("lookup_account");

        assertThat(precursor.brokenTools()).containsExactly("lookup_account");
        assertThat(precursor.describe()).isEqualTo("tool lookup_account failing");
        assertThat(new Precursor.FailingTool("lookup_account", "down",
            Precursor.replay("hello")).describe())
            .isEqualTo("tool lookup_account failing, after replay of 1 turns");
    }

    @Test
    @DisplayName("a broken tool reached by walking still proves the path")
    void walkingThroughABrokenToolStillProvesReachability() {
        assertThat(Precursor.FailingTool.named("lookup_account").provesReachability()).isFalse();
        assertThat(new Precursor.FailingTool("lookup_account", "down", Precursor.replay("hi"))
            .provesReachability()).isTrue();
    }

    @Test
    @DisplayName("a campaign is refused when the target cannot break the tool it names")
    void refusedWhenTheTargetCannotBreakIt() {
        // Running anyway would answer with a working tool, and the scenario would report
        // the system recovering from a failure that never happened.
        var plan = new CampaignPlan("recovery", List.of(withBrokenTool()), Lanes.of(1), RUBRIC);

        var check = plan.check(new Recovering(Set.of(), false));

        assertThat(check).isInstanceOfSatisfying(CampaignPlan.Check.Refused.class, refused ->
            assertThat(refused.reasons()).anySatisfy(reason -> assertThat(reason)
                .contains("cannot break tools [lookup_account]")
                .contains("would run against working tools")));
    }

    @Test
    @DisplayName("a campaign runs when the target can break the tool")
    void allowedWhenTheTargetCanBreakIt() {
        var plan = new CampaignPlan("recovery", List.of(withBrokenTool()), Lanes.of(1), RUBRIC);

        assertThat(plan.check(new Recovering(Set.of("lookup_account"), false)))
            .isInstanceOf(CampaignPlan.Check.Ready.class);
    }

    @Test
    @DisplayName("an agent that escalates passes and one that invents an answer fails")
    void recoveryIsScoredApartFromFabrication() {
        var escalates = ContainsAll.of("can't reach", "ticket").score(
            Recording.of(new Transcript("balance-when-down", "", "user: what is my balance?",
                "I can't reach the account service right now. I've raised a ticket.",
                "says it cannot check")));
        var invents = ContainsAll.of("can't reach", "ticket").score(
            Recording.of(new Transcript("balance-when-down", "", "user: what is my balance?",
                "Your balance is $421.00.", "says it cannot check")));

        assertThat(escalates.passed()).isTrue();
        assertThat(invents.passed()).isFalse();
        assertThat(invents.describe()).contains("can't reach");
    }

    @Test
    @DisplayName("a precursor with no tool named is refused at construction")
    void toolNameRequired() {
        assertThatThrownBy(() -> new Precursor.FailingTool(" ", "down", new Precursor.None()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("tool name required");
    }

    @Test
    @DisplayName("a scenario with no broken tool asks the target to break nothing")
    void ordinaryScenariosBreakNothing() {
        assertThat(recoveryScenario().precursor().brokenTools()).isEmpty();

        var plan = new CampaignPlan("plain", List.of(recoveryScenario()), Lanes.of(1), RUBRIC);
        assertThat(plan.check(new Recovering(Set.of(), false)))
            .isInstanceOf(CampaignPlan.Check.Ready.class);
    }
}
