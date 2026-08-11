package io.akka.evalkit.application;

import akka.javasdk.DependencyProvider;
import akka.javasdk.ServiceSetup;
import akka.javasdk.annotations.Setup;
import io.akka.evalkit.domain.Precursor;
import io.akka.evalkit.domain.RunOutcome;
import io.akka.evalkit.domain.Scenario;
import io.akka.evalkit.domain.ScenarioSource;
import io.akka.evalkit.domain.SystemUnderTest;
import io.akka.evalkit.domain.Verdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires the campaign workflow's collaborators for tests.
 *
 * <p>{@link CampaignWorkflow} takes a source, a target and a judge by constructor
 * injection, which in Akka means a {@code DependencyProvider} from a service setup. That
 * is the intended shape for a library: the consuming service decides what it is evaluating
 * and how, and evalkit only says what it needs.
 *
 * <p>The fakes here are deliberately controllable rather than realistic &mdash; the
 * subject of these tests is the workflow's durability and arithmetic, not a model.
 */
@Setup
public class TestHarnessSetup implements ServiceSetup {

    /** Scenarios by campaign id, so one runtime can serve several test campaigns. */
    public static final class Source implements ScenarioSource {

        /** How many scenarios each campaign has. Empty means "no such campaign". */
        public static final java.util.Map<String, Integer> SIZES = new java.util.HashMap<>();

        /** Campaigns whose scenarios name a fixture the target does not have. */
        public static final Set<String> BAD_FIXTURE = java.util.concurrent.ConcurrentHashMap.newKeySet();

        /** Pages served, so the test can prove the dataset was never loaded whole. */
        public static final AtomicInteger PAGES = new AtomicInteger();

        @Override
        public int size(String campaignId) {
            return SIZES.getOrDefault(campaignId, 0);
        }

        @Override
        public List<Scenario> page(String campaignId, int offset, int limit) {
            PAGES.incrementAndGet();
            int size = size(campaignId);
            var out = new ArrayList<Scenario>();
            for (int i = offset; i < Math.min(size, offset + limit); i++) {
                var precursor = BAD_FIXTURE.contains(campaignId)
                    ? Precursor.Fixture.named("nonexistent")
                    : Precursor.Fixture.named("ready");
                out.add(new Scenario(campaignId + "-" + i, Optional.empty(), precursor,
                    "what happens next?", "The agent should explain what happens next."));
            }
            return out;
        }
    }

    public static final class Target implements SystemUnderTest {
        @Override
        public Prepared prepare(Precursor precursor) {
            return new Prepared.Ready("session", "");
        }

        @Override
        public Reply submit(String sessionId, String userText) {
            return Reply.of("here is what happens next");
        }

        @Override
        public java.util.Map<String, String> fixtures() {
            return java.util.Map.of("ready", "a prepared state");
        }
    }

    /** Scores everything the same, unless told to fail from a given index. */
    public static final class Judge implements CampaignRunner.Judge {

        public static volatile int score = 9;
        public static final AtomicInteger judged = new AtomicInteger();

        @Override
        public RunOutcome score(io.akka.evalkit.domain.Transcript transcript,
                                io.akka.evalkit.domain.Rubric rubric) {
            judged.incrementAndGet();
            return new RunOutcome.Scored(
                Verdict.of(transcript.scenarioName(), rubric, score, ""));
        }
    }

    private final Source source = new Source();
    private final Target target = new Target();
    private final Judge judge = new Judge();

    @Override
    public DependencyProvider createDependencyProvider() {
        return new DependencyProvider() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T getDependency(Class<T> type) {
                if (type == ScenarioSource.class) return (T) source;
                if (type == SystemUnderTest.class) return (T) target;
                if (type == CampaignRunner.Judge.class) return (T) judge;
                throw new IllegalArgumentException("no dependency for " + type);
            }
        };
    }
}
