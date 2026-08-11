package io.akka.evalkit.application;

import akka.Done;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.StepName;
import akka.javasdk.workflow.Workflow;
import io.akka.evalkit.domain.CampaignPlan;
import io.akka.evalkit.domain.CampaignReport;
import io.akka.evalkit.domain.Lanes;
import io.akka.evalkit.domain.Rubric;
import io.akka.evalkit.domain.ScenarioSource;
import io.akka.evalkit.domain.SystemUnderTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static akka.Done.done;

/**
 * A campaign, made durable.
 *
 * <p>Campaigns are long. The 10% calibration sample took three minutes; the full dataset
 * at the same rate is over half an hour, and a real campaign that also executes scenarios
 * against a service is longer again. A process that loses all of it to a restart or a
 * deploy is a process nobody runs on anything that matters &mdash; which is the whole
 * reason this harness sits on Akka rather than in a script.
 *
 * <p><b>Waves, not one pass.</b> Each step takes a page of scenarios, runs them across
 * the configured lanes, and folds the counts into state before transitioning. Recovery
 * therefore costs at most one wave, and resuming reads a cursor rather than a dataset.
 *
 * <p><b>State holds counts, not outcomes.</b> Workflow state is serialised on every
 * transition; carrying thousands of transcripts through each one would cost more than
 * running the campaign. {@link CampaignReport} accumulates, and per-transcript detail
 * belongs wherever the judge's {@code EvaluationResult} already goes — metrics and traces.
 */
@Component(id = "eval-campaign")
public class CampaignWorkflow extends Workflow<CampaignWorkflow.State> {

    /**
     * @param wave how many scenarios to take per durable step. Not the lane count: lanes
     *             are how many run at once, this is how much is lost to a restart
     */
    public record Start(String campaignId, String rubricId, int rubricVersion,
                        int lanes, int wave) {

        public Start {
            if (campaignId == null || campaignId.isBlank()) {
                throw new IllegalArgumentException("campaignId required");
            }
            if (wave < 1) throw new IllegalArgumentException("wave must be at least 1");
        }
    }

    public enum Status { CHECKING, RUNNING, REFUSED, COMPLETE }

    public record State(String campaignId, String rubricId, int rubricVersion,
                        int lanes, int wave, int total, int cursor,
                        CampaignReport tally, Status status, List<String> notes) {

        public State {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }

        static State opening(Start start) {
            return new State(start.campaignId(), start.rubricId(), start.rubricVersion(),
                start.lanes(), start.wave(), 0, 0, CampaignReport.empty(),
                Status.CHECKING, List.of());
        }

        State refused(List<String> reasons) {
            return new State(campaignId, rubricId, rubricVersion, lanes, wave, total, cursor,
                tally, Status.REFUSED, reasons);
        }

        State running(int size) {
            return new State(campaignId, rubricId, rubricVersion, lanes, wave, size, 0,
                tally, Status.RUNNING, notes);
        }

        State advanced(int done, CampaignReport waveTally) {
            return new State(campaignId, rubricId, rubricVersion, lanes, wave, total,
                cursor + done, tally.plus(waveTally), status, notes);
        }

        State complete(List<String> finalNotes) {
            return new State(campaignId, rubricId, rubricVersion, lanes, wave, total, cursor,
                tally, Status.COMPLETE, finalNotes);
        }

        public boolean hasMore() {
            return cursor < total;
        }

        public int progressPercent() {
            return total == 0 ? 0 : 100 * cursor / total;
        }
    }

    private final ScenarioSource scenarios;
    private final SystemUnderTest target;
    private final CampaignRunner.Judge judge;

    public CampaignWorkflow(ScenarioSource scenarios, SystemUnderTest target,
                            CampaignRunner.Judge judge) {
        this.scenarios = scenarios;
        this.target = target;
        this.judge = judge;
    }

    @Override
    public WorkflowSettings settings() {
        return WorkflowSettings.builder()
            // A wave is many model calls wide. The default would time out a healthy one.
            .defaultStepTimeout(Duration.ofMinutes(10))
            // One retry, then stop. A wave that fails twice is not a flaky call, and
            // retrying a costly wave repeatedly spends a provider budget on a bug.
            .defaultStepRecovery(RecoverStrategy.maxRetries(1).failoverTo(CampaignWorkflow::halt))
            .build();
    }

    // ---- commands ----

    public Effect<Done> start(Start command) {
        if (currentState() != null) return effects().error("campaign already started");
        return effects().updateState(State.opening(command))
            .transitionTo(CampaignWorkflow::check)
            .thenReply(done());
    }

    public ReadOnlyEffect<State> get() {
        return currentState() == null ? effects().error("no such campaign")
            : effects().reply(currentState());
    }

    // ---- steps ----

    /**
     * Refuse before spending anything.
     *
     * <p>A separate durable step rather than part of {@code start} so that the reasons
     * survive: a campaign refused at minute zero should still be inspectable an hour later
     * by whoever asks why it produced nothing.
     */
    @StepName("check")
    private StepEffect check() {
        var state = currentState();
        int size = scenarios.size(state.campaignId());
        if (size == 0) {
            return stepEffects()
                .updateState(state.refused(List.of("no scenarios for " + state.campaignId())))
                .thenEnd();
        }

        // Checked against the first page. The alternative is loading the dataset to
        // validate it, which is what paging exists to avoid — and fixture names repeat,
        // so a page is a fair sample of the names in play.
        var plan = new CampaignPlan(state.campaignId(),
            scenarios.page(state.campaignId(), 0, Math.min(size, state.wave())),
            Lanes.of(state.lanes()), rubric(state));

        if (plan.check(target) instanceof CampaignPlan.Check.Refused refused) {
            return stepEffects().updateState(state.refused(refused.reasons())).thenEnd();
        }
        return stepEffects().updateState(state.running(size))
            .thenTransitionTo(CampaignWorkflow::runWave);
    }

    /** One durable page: run it, fold the counts in, come back for the next. */
    @StepName("wave")
    private StepEffect runWave() {
        var state = currentState();
        var page = scenarios.page(state.campaignId(), state.cursor(), state.wave());

        if (page.isEmpty()) {
            // The source ran dry earlier than size() promised. Ending is right — looping
            // on an empty page would spin forever on a cursor that cannot advance.
            return stepEffects().updateState(state.complete(finalNotes(state)))
                .thenEnd();
        }

        var plan = new CampaignPlan(state.campaignId(), page, Lanes.of(state.lanes()), rubric(state));
        var result = CampaignRunner.run(plan, target, judge);
        var advanced = state.advanced(page.size(), result.report());

        return advanced.hasMore()
            ? stepEffects().updateState(advanced).thenTransitionTo(CampaignWorkflow::runWave)
            : stepEffects().updateState(advanced.complete(finalNotes(advanced))).thenEnd();
    }

    /** Retries exhausted. The tally so far is kept — partial evidence is still evidence. */
    @StepName("halt")
    private StepEffect halt() {
        var state = currentState();
        var notes = new ArrayList<>(finalNotes(state));
        notes.add("campaign stopped after a failing wave at scenario " + state.cursor()
            + " of " + state.total() + "; counts below cover what completed");
        return stepEffects().updateState(state.complete(notes)).thenEnd();
    }

    // ---- helpers ----

    private Rubric rubric(State state) {
        return Rubric.load(state.rubricId(), state.rubricVersion());
    }

    /** The caveats that travel with the numbers. See {@link CampaignReport}. */
    private static List<String> finalNotes(State state) {
        var notes = new ArrayList<String>();
        var tally = state.tally();
        if (!tally.isTrustworthy() && tally.total() > 0) {
            notes.add("pass rate is not quotable: " + tally.withoutEvidence()
                + " runs produced no evidence and " + tally.review() + " are undecided");
        }
        if (tally.scorerFailed() > 0) {
            notes.add(tally.scorerFailed() + " runs failed inside a scorer, which is a defect "
                + "in this kit rather than a finding about the system");
        }
        if (!tally.provesAnyReachability() && tally.judged() > 0) {
            notes.add("entirely seeded — this campaign cannot detect a state that has "
                + "become unreachable");
        }
        return notes;
    }
}
