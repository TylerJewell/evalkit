package io.akka.evalkit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Refusing a campaign whose evaluator bindings name nothing.
 *
 * <p>An evaluator binds to an agent in configuration. A name nobody registered is not a
 * startup error and not a runtime error: the binding matches no component, so it scores
 * nothing, and a report with no rows for that evaluator reads as an agent that behaved.
 */
@DisplayName("CampaignPlan · evaluator bindings checked before the run")
class EvaluatorBindingCheckTest {

    private static final class Target implements SystemUnderTest {

        @Override
        public Map<String, String> fixtures() {
            return Map.of("signed-in", "a signed-in customer");
        }

        @Override
        public Prepared prepare(Precursor precursor) {
            return new Prepared.Ready("session-1", "");
        }

        @Override
        public Reply submit(String sessionId, String userText) {
            return Reply.of("the refund takes 30 days");
        }
    }

    private static CampaignPlan plan() {
        return new CampaignPlan("refund-policy",
            List.of(new Scenario("refund-timing", Optional.empty(),
                Precursor.Fixture.named("signed-in"),
                "when do I get my refund?", "States the 30-day window")),
            Lanes.of(1), Rubric.load("scenario-judge", 3));
    }

    @Test
    @DisplayName("a binding naming a registered evaluator is ready")
    void aRegisteredBindingIsReady() {
        var check = plan().check(new Target(),
            List.of("evalkit-tool-permission"),
            List.of("evalkit-tool-permission", "evalkit-turn-relevancy"));

        assertThat(check).isInstanceOf(CampaignPlan.Check.Ready.class);
    }

    /**
     * The case this check is known to catch.
     *
     * <p>Without it the campaign runs, the binding matches nothing, and the report shows no
     * findings from an evaluator that never ran.
     */
    @Test
    @DisplayName("a binding naming no registered evaluator is refused, and named")
    void anUnregisteredBindingIsRefused() {
        var check = plan().check(new Target(),
            List.of("evalkit-turn-relevancy", "evalkit-typo-here"),
            List.of("evalkit-turn-relevancy"));

        assertThat(check).isInstanceOf(CampaignPlan.Check.Refused.class);
        assertThat(((CampaignPlan.Check.Refused) check).reasons())
            .anySatisfy(reason -> assertThat(reason).contains("evalkit-typo-here"));
    }

    @Test
    @DisplayName("a campaign binding no evaluator is ready")
    void noBindingsIsReady() {
        var check = plan().check(new Target(), List.of(), List.of());

        assertThat(check).isInstanceOf(CampaignPlan.Check.Ready.class);
    }

    /** The fixture refusal still applies, so both reasons reach the caller. */
    @Test
    @DisplayName("a plan failing on fixtures and on bindings reports both")
    void bothRefusalsAreReported() {
        var plan = new CampaignPlan("refund-policy",
            List.of(new Scenario("refund-timing", Optional.empty(),
                Precursor.Fixture.named("never-implemented"),
                "when do I get my refund?", "States the 30-day window")),
            Lanes.of(1), Rubric.load("scenario-judge", 3));

        var check = plan.check(new Target(), List.of("evalkit-typo-here"), List.of());

        assertThat(check).isInstanceOf(CampaignPlan.Check.Refused.class);
        var reasons = ((CampaignPlan.Check.Refused) check).reasons();
        assertThat(reasons).hasSize(2);
        assertThat(reasons).anySatisfy(r -> assertThat(r).contains("never-implemented"));
        assertThat(reasons).anySatisfy(r -> assertThat(r).contains("evalkit-typo-here"));
    }
}
