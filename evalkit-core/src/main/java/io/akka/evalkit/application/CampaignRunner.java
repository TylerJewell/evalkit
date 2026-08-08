package io.akka.evalkit.application;

import io.akka.evalkit.domain.CampaignPlan;
import io.akka.evalkit.domain.CampaignReport;
import io.akka.evalkit.domain.Lanes;
import io.akka.evalkit.domain.Precursor;
import io.akka.evalkit.domain.RunOutcome;
import io.akka.evalkit.domain.Rubric;
import io.akka.evalkit.domain.Scenario;
import io.akka.evalkit.domain.ScenarioRunner;
import io.akka.evalkit.domain.SystemUnderTest;
import io.akka.evalkit.domain.Transcript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs a checked plan: execute each scenario, judge what needs judging, aggregate.
 *
 * <p>Workers over a shared queue &mdash; see {@link Lanes} for why not a partition. The
 * queue self-balances, which matters here because transcripts in the corpora run from 2
 * messages to 82 and a fixed split would leave one worker grinding while the rest idle.
 *
 * <p>The judge arrives as a function rather than an {@code Agent}, so the orchestration
 * is testable without a model. {@link #judgingWith} adapts the real one.
 */
public final class CampaignRunner {

    /** Scores a transcript, or explains why it could not. */
    @FunctionalInterface
    public interface Judge {
        RunOutcome score(Transcript transcript, Rubric rubric);
    }

    public record Result(CampaignReport report, Lanes.Utilisation utilisation,
                         List<Completed> completed, List<String> notes) {

        /** The outcomes alone, for callers that need no scenario. */
        public List<RunOutcome> outcomes() {
            return completed.stream().map(Completed::outcome).toList();
        }
    }

    private CampaignRunner() {}

    /**
     * One scenario's result, kept with the precursor that produced it.
     *
     * <p>One record rather than two lists: workers append concurrently, so parallel lists
     * carry no guarantee that {@code outcome[i]} and {@code scenario[i]} describe the
     * same scenario — and the report's "walked" count is derived from that pairing.
     *
     * <p>The scenario travels with the outcome for the same reason, and is public because
     * every caller reporting a result needs to name what produced it. Handing back a bare
     * list of outcomes puts callers back to zipping against {@code plan.scenarios()}, which
     * is in submission order while results arrive in completion order.
     */
    public record Completed(RunOutcome outcome, Scenario scenario) {
        public Precursor precursor() {
            return scenario.precursor();
        }
    }

    /** A campaign settled by one rubric judge, with node comparison where a node is named. */
    public static Result run(CampaignPlan plan, SystemUnderTest target, Judge judge) {
        return execute(plan, scenario -> runOne(scenario, target, judge, plan.rubric()));
    }

    /**
     * A campaign settled by three scorer families.
     *
     * <p>The router decides per scenario. Node comparison happens here, where the reported
     * node and the transcript are both in hand.
     */
    public static Result run(CampaignPlan plan, SystemUnderTest target,
                             io.akka.evalkit.domain.ScorerRouter router) {
        return execute(plan, scenario -> runOne(scenario, target, router));
    }

    private static Result execute(CampaignPlan plan,
                                  java.util.function.Function<Scenario, RunOutcome> runOne) {
        var completed = Collections.synchronizedList(new ArrayList<Completed>());
        var busy = new AtomicLong();

        long began = System.nanoTime();
        try (var pool = Executors.newFixedThreadPool(plan.lanes().configured())) {
            List<Callable<Void>> work = plan.scenarios().stream()
                .map(scenario -> (Callable<Void>) () -> {
                    long start = System.nanoTime();
                    try {
                        completed.add(new Completed(runOne.apply(scenario), scenario));
                    } catch (Throwable t) {
                        // Every scenario must produce a row. invokeAll parks a thrown
                        // exception in a Future nobody reads, so an uncaught throw would
                        // drop the scenario silently and the campaign would report a
                        // smaller total than it was asked to run.
                        completed.add(new Completed(
                            new RunOutcome.NotReached(RunOutcome.Cause.NO_REPLY,
                                "run threw: " + rootMessage(t), scenario.precursor()),
                            scenario));
                    } finally {
                        busy.addAndGet(System.nanoTime() - start);
                    }
                    return null;
                })
                .toList();
            pool.invokeAll(work);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("campaign " + plan.id() + " interrupted", e);
        }
        var wallClock = Duration.ofNanos(System.nanoTime() - began);

        var snapshot = List.copyOf(completed);
        if (snapshot.size() != plan.scenarios().size()) {
            throw new IllegalStateException("campaign " + plan.id() + " produced "
                + snapshot.size() + " outcomes for " + plan.scenarios().size() + " scenarios");
        }
        var outcomes = snapshot.stream().map(Completed::outcome).toList();
        var report = CampaignReport.of(outcomes, snapshot.stream().map(Completed::precursor).toList());
        var utilisation = plan.lanes().over(Duration.ofNanos(busy.get()), wallClock, outcomes.size());
        return new Result(report, utilisation, snapshot, notes(report, utilisation));
    }

    private static RunOutcome runOne(Scenario scenario, SystemUnderTest target,
                                     Judge judge, Rubric rubric) {
        var execution = ScenarioRunner.execute(scenario, target);
        if (execution instanceof ScenarioRunner.Execution.NotReached notReached) {
            return new RunOutcome.NotReached(notReached.cause(), notReached.reason(),
                scenario.precursor());
        }
        var produced = (ScenarioRunner.Execution.Produced) execution;

        // The routing this harness exists for. A scenario naming a decision is settled by
        // comparison — no model call, no variance, no cost. Only the ones that genuinely
        // reach a model boundary are judged.
        if (scenario.specNode().isPresent()) {
            return io.akka.evalkit.domain.SpecNodeMatch
                .assertReached(scenario.specNode().orElseThrow(), produced.node());
        }

        var transcript = produced.transcript();
        try {
            return judge.score(transcript, rubric);
        } catch (RuntimeException e) {
            // A judge that refuses — a content filter, a timeout, an unreadable reply —
            // is absent evidence, never a verdict. This already happened once against a
            // real provider, so it is handled rather than anticipated.
            return new RunOutcome.Unscoreable(rootMessage(e));
        }
    }

    private static RunOutcome runOne(Scenario scenario, SystemUnderTest target,
                                     io.akka.evalkit.domain.ScorerRouter router) {
        var execution = ScenarioRunner.execute(scenario, target);
        if (execution instanceof ScenarioRunner.Execution.NotReached notReached) {
            return new RunOutcome.NotReached(notReached.cause(), notReached.reason(),
                scenario.precursor());
        }
        var produced = (ScenarioRunner.Execution.Produced) execution;

        var scorer = router.scorerFor(scenario);
        if (scorer.isEmpty()) {
            return io.akka.evalkit.domain.SpecNodeMatch
                .assertReached(scenario.specNode().orElseThrow(), produced.node());
        }

        try {
            return scorer.orElseThrow().score(produced.recording());
        } catch (RuntimeException e) {
            // Every scorer family reaches here. A metric that threw computed nothing, and
            // a run with no computed result is no more a finding than a refused judge.
            return new RunOutcome.Unscoreable(rootMessage(e));
        }
    }

    /**
     * What the numbers are not allowed to be quoted without.
     *
     * <p>Every caveat here corresponds to a way a campaign can produce a confident figure
     * that means nothing.
     */
    private static List<String> notes(CampaignReport report, Lanes.Utilisation utilisation) {
        var notes = new ArrayList<String>();
        if (!report.isTrustworthy()) {
            notes.add("pass rate is not quotable: "
                + (report.notReached() + report.unscoreable()) + " runs produced no evidence and "
                + report.review() + " are undecided");
        }
        if (!report.provesAnyReachability() && report.judged() > 0) {
            notes.add("entirely seeded — this campaign cannot detect a state that has "
                + "become unreachable");
        }
        if (!utilisation.saturated()) {
            notes.add("lanes were not the constraint (" + utilisation.summary() + ")");
        }
        return notes;
    }

    static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        var message = cur.getMessage();
        return message == null ? cur.getClass().getSimpleName()
            : message.substring(0, Math.min(200, message.length()));
    }
}
