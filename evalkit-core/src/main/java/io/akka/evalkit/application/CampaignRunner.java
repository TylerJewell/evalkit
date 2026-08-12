package io.akka.evalkit.application;

import io.akka.evalkit.domain.CampaignPlan;
import io.akka.evalkit.domain.CampaignReport;
import io.akka.evalkit.domain.Lanes;
import io.akka.evalkit.domain.InconclusiveScore;
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
 * queue self-balances, which matters here because transcripts in the datasets run from 2
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

    /**
     * What a campaign produced.
     *
     * <p>{@code completed} holds one row per run and {@code requirements} groups those rows
     * by the scenario that produced them. The two count different things once a campaign
     * repeats: a dataset of 80 scenarios run five times each is 400 completed rows and 80
     * requirements. {@link CampaignReport} counts rows, and the report a reader sees is
     * rendered from the requirements, so a flaky scenario is one varied requirement rather
     * than a mixture of passes and failures.
     */
    public record Result(CampaignReport report, Lanes.Utilisation utilisation,
                         List<Completed> completed,
                         List<io.akka.evalkit.domain.RequirementResult> requirements,
                         List<String> notes) {

        /** The outcomes alone, for callers that need no scenario. */
        public List<RunOutcome> outcomes() {
            return completed.stream().map(Completed::outcome).toList();
        }
    }

    /**
     * The runs of each scenario, gathered back into one requirement.
     *
     * <p>Requirements follow the plan and each requirement's runs follow the order they
     * were submitted in. Workers append as they finish, and runs of one scenario are
     * indistinguishable once they are in the list, so reading the list order as the run
     * order would put a requirement's marks in whatever sequence the lanes produced. The
     * report prints those marks as a sequence and says they are in order, so the order
     * has to be one.
     */
    private static List<io.akka.evalkit.domain.RequirementResult> requirements(
            CampaignPlan plan, List<Completed> completed) {
        var byScenario = new java.util.LinkedHashMap<String, List<Completed>>();
        for (Scenario scenario : plan.scenarios()) byScenario.put(scenario.id(), new ArrayList<>());
        for (Completed row : completed) {
            byScenario.computeIfAbsent(row.scenario().id(), ignored -> new ArrayList<>()).add(row);
        }
        return byScenario.entrySet().stream()
            .filter(entry -> !entry.getValue().isEmpty())
            .map(entry -> new io.akka.evalkit.domain.RequirementResult(
                entry.getValue().get(0).scenario(),
                entry.getValue().stream()
                    .sorted(java.util.Comparator.comparingInt(Completed::attempt))
                    .map(row -> new io.akka.evalkit.domain.RequirementResult.Run(
                        row.outcome(),
                        row.observation().flatMap(io.akka.evalkit.domain.Observation::latency)))
                    .toList()))
            .toList();
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
    public record Completed(RunOutcome outcome, Scenario scenario,
                            java.util.Optional<io.akka.evalkit.domain.Observation> observation,
                            int attempt) {

        public Completed(RunOutcome outcome, Scenario scenario,
                         java.util.Optional<io.akka.evalkit.domain.Observation> observation) {
            this(outcome, scenario, observation, 0);
        }

        public Completed(RunOutcome outcome, Scenario scenario) {
            this(outcome, scenario, java.util.Optional.empty(), 0);
        }

        public Precursor precursor() {
            return scenario.precursor();
        }
    }

    /**
     * What one scenario produced, kept together for the reason {@link Completed} exists.
     *
     * <p>The observation is absent for a run that never reached the question, because nothing
     * was said and there is nothing to record.
     */
    private record Ran(RunOutcome outcome,
                       java.util.Optional<io.akka.evalkit.domain.Observation> observation) {

        static Ran of(RunOutcome outcome) {
            return new Ran(outcome, java.util.Optional.empty());
        }

        static Ran of(RunOutcome outcome, io.akka.evalkit.domain.Observation observation) {
            return new Ran(outcome, java.util.Optional.of(observation));
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
                                  java.util.function.Function<Scenario, Ran> runOne) {
        var completed = Collections.synchronizedList(new ArrayList<Completed>());
        var busy = new AtomicLong();

        long began = System.nanoTime();
        try (var pool = Executors.newFixedThreadPool(plan.lanes().configured())) {
            // Each scenario is submitted once per repeat. Repeats are separate units of
            // work rather than a loop inside one, so a slow scenario's runs spread across
            // lanes instead of holding one lane for all of them.
            record Attempt(Scenario scenario, int number) {}
            List<Callable<Void>> work = plan.scenarios().stream()
                .flatMap(scenario -> java.util.stream.IntStream.range(0, plan.repeats())
                    .mapToObj(n -> new Attempt(scenario, n)))
                .map(attempt -> (Callable<Void>) () -> {
                    Scenario scenario = attempt.scenario();
                    long start = System.nanoTime();
                    try {
                        var ran = runOne.apply(scenario);
                        completed.add(new Completed(ran.outcome(), scenario, ran.observation(),
                            attempt.number()));
                    } catch (Throwable t) {
                        // Every scenario must produce a row. invokeAll parks a thrown
                        // exception in a Future nobody reads, so an uncaught throw would
                        // drop the scenario silently and the campaign would report a
                        // smaller total than it was asked to run.
                        completed.add(new Completed(
                            new RunOutcome.NotReached(RunOutcome.Cause.NO_REPLY,
                                "run threw: " + rootMessage(t), scenario.precursor()),
                            scenario, java.util.Optional.empty(), attempt.number()));
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
        if (snapshot.size() != plan.runs()) {
            throw new IllegalStateException("campaign " + plan.id() + " produced "
                + snapshot.size() + " outcomes for " + plan.runs() + " runs");
        }
        var outcomes = snapshot.stream().map(Completed::outcome).toList();
        var report = CampaignReport.of(outcomes, snapshot.stream().map(Completed::precursor).toList());
        var utilisation = plan.lanes().over(Duration.ofNanos(busy.get()), wallClock, outcomes.size());
        return new Result(report, utilisation, snapshot,
            requirements(plan, snapshot), notes(report, utilisation));
    }

    private static Ran runOne(Scenario scenario, SystemUnderTest target,
                              Judge judge, Rubric rubric) {
        var execution = ScenarioRunner.execute(scenario, target);
        if (execution instanceof ScenarioRunner.Execution.NotReached notReached) {
            return Ran.of(new RunOutcome.NotReached(notReached.cause(), notReached.reason(),
                scenario.precursor()));
        }
        var produced = (ScenarioRunner.Execution.Produced) execution;
        var observation = produced.observation();

        // The routing this harness exists for. A scenario naming a decision is settled by
        // comparison — no model call, no variance, no cost. Only the ones that genuinely
        // reach a model boundary are judged.
        if (scenario.specNode().isPresent()) {
            return Ran.of(io.akka.evalkit.domain.SpecNodeMatch
                .assertReached(scenario.specNode().orElseThrow(), produced.node()), observation);
        }

        var transcript = produced.transcript();
        try {
            return Ran.of(judge.score(transcript, rubric), observation);
        } catch (InconclusiveScore declined) {
            // The judge ran and would not answer — a content filter, an unreadable reply.
            // Absent evidence about the transcript, never a verdict.
            return Ran.of(new RunOutcome.Inconclusive(rootMessage(declined)), observation);
        } catch (RuntimeException e) {
            // Anything else is this kit breaking, which is a different row and a different
            // reading. See RunOutcome.Failed.
            return Ran.of(new RunOutcome.Failed(rootMessage(e)), observation);
        }
    }

    private static Ran runOne(Scenario scenario, SystemUnderTest target,
                              io.akka.evalkit.domain.ScorerRouter router) {
        var execution = ScenarioRunner.execute(scenario, target);
        if (execution instanceof ScenarioRunner.Execution.NotReached notReached) {
            return Ran.of(new RunOutcome.NotReached(notReached.cause(), notReached.reason(),
                scenario.precursor()));
        }
        var produced = (ScenarioRunner.Execution.Produced) execution;
        var observation = produced.observation();

        var scorer = router.scorerFor(scenario);
        if (scorer.isEmpty()) {
            return Ran.of(io.akka.evalkit.domain.SpecNodeMatch
                .assertReached(scenario.specNode().orElseThrow(), produced.node()), observation);
        }

        try {
            return Ran.of(scorer.orElseThrow().score(observation), observation);
        } catch (InconclusiveScore declined) {
            return Ran.of(new RunOutcome.Inconclusive(rootMessage(declined)), observation);
        } catch (RuntimeException e) {
            // Every scorer family reaches here. A metric that threw computed nothing, and a
            // throw that is not a declared absence is this kit failing.
            return Ran.of(new RunOutcome.Failed(rootMessage(e)), observation);
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
            notes.add("pass rate is not quotable: " + report.withoutEvidence()
                + " runs produced no evidence and " + report.review() + " are undecided");
        }
        if (report.scorerFailed() > 0) {
            // Not a caveat about the system. A scorer that threw is a defect here, and it
            // belongs on its own line so it is fixed rather than absorbed.
            notes.add(report.scorerFailed() + " runs failed inside a scorer, which is a "
                + "defect in this kit rather than a finding about the system");
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
