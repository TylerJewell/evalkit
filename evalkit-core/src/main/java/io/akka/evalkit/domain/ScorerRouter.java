package io.akka.evalkit.domain;

import io.akka.evalkit.metric.MetricRef;

import java.util.Map;
import java.util.Optional;

/**
 * Decides how each scenario is settled, before any call goes out.
 *
 * <p>Routing at plan time is what keeps a campaign affordable. A scenario naming a
 * specification node is exercising a decision with a right answer, and sending it to a
 * model buys a slightly random opinion about something that has none. In one recorded
 * dataset, 510 of 514 scenarios named a node.
 */
@FunctionalInterface
public interface ScorerRouter {

    /**
     * The scorer for a scenario, or empty when the runner settles it.
     *
     * <p>Empty covers node comparison. That case needs the node the target reported
     * alongside the transcript, the runner is the only place both are in hand, and
     * {@link SpecNodeMatch} does the comparison there.
     */
    Optional<Scorer> scorerFor(Scenario scenario);

    /**
     * Reads the scenario and sends it where its own expectation points.
     *
     * <p>A named node is left to the runner. A named metric routes to that metric.
     * Required phrases route to a comparison over the reply. Anything left states its
     * expectation in prose, which only a judge can read.
     *
     * <p>A scenario naming a metric this campaign did not register produces
     * {@link RunOutcome.Inconclusive} at run time, because a scorer that cannot run
     * produced no evidence and the run is not a finding about the service.
     *
     * @param judge   settles the scenarios that name neither a node nor a metric
     * @param metrics every metric a scenario in this campaign may name
     */
    static ScorerRouter byExpectation(Scorer judge, Map<MetricRef, Scorer> metrics) {
        var byRef = Map.copyOf(metrics);
        return scenario -> {
            if (scenario.specNode().isPresent()) return Optional.empty();

            if (!scenario.requiredPhrases().isEmpty()) {
                return Optional.of(ContainsAll.of(scenario.requiredPhrases()));
            }

            if (scenario.metric().isPresent()) {
                MetricRef ref = scenario.metric().orElseThrow();
                Scorer scorer = byRef.get(ref);
                return Optional.of(scorer != null ? scorer
                    : observation -> new RunOutcome.Inconclusive(
                        "no metric registered for " + ref.label()));
            }
            return Optional.of(judge);
        };
    }

    /** A campaign with no metrics, which is every campaign written before they existed. */
    static ScorerRouter judgingEverything(Scorer judge) {
        return byExpectation(judge, Map.of());
    }
}
