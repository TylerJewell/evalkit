package io.akka.evalkit.application;

import io.akka.evalkit.domain.RunOutcome;
import akka.javasdk.agent.Agent;
import akka.javasdk.agent.EvaluationResult;
import akka.javasdk.agent.MemoryProvider;
import akka.javasdk.annotations.AgentRole;
import akka.javasdk.annotations.Component;
import io.akka.evalkit.domain.Band;
import io.akka.evalkit.domain.Rubric;
import io.akka.evalkit.domain.Transcript;
import io.akka.evalkit.domain.Grade;

/**
 * Scores a finished transcript against an expected outcome.
 *
 * <p>The prompt is not in this class. It is loaded from {@code rubrics/} and passed in, so
 * that the judge is the mechanism and the rubric is the data &mdash; see {@link Rubric}
 * for why that separation is what makes a score interpretable later.
 *
 * <p><b>Memory is off.</b> Judging is per-transcript and independent; a judge that
 * remembered the last twenty scenarios would drift toward its own recent scores, and two
 * campaigns run in a different order would disagree for no reason connected to the
 * systems being judged.
 *
 * <p><b>The rubric is used verbatim, and it decides what is asked.</b> This class adds no
 * instruction of its own &mdash; no reason field, no structured schema &mdash; because
 * adding one would change the model's task and break comparability with every score already
 * recorded under that rubric. {@code scenario-judge} v2 asks for "a single value from 1 to
 * 10" and gets a score with nothing beside it. v3 asks for the score and one sentence, on
 * the same bands, and is a separate rubric run alongside v2 rather than a change to it.
 * {@link Rubric#statesExplanation()} is how the reply is read either way.
 */
@Component(
    id = "scenario-judge",
    name = "Scenario Judge",
    description = """
        Measures how well a recorded conversation matches its expected outcome, \
        returning a score from 1 to 10.""")
@AgentRole("evaluator")
public class ScenarioJudge extends Agent {

    /** A transcript plus the rubric to judge it by. */
    public record JudgeRequest(Transcript transcript, Rubric rubric) {}

    /**
     * @param explanation       the band, the score and the rubric that produced them, for a
     *                          trace to be readable without opening the transcript. The name
     *                          is {@link EvaluationResult}'s
     * @param passed            only {@link Band#FAITHFUL} counts — see {@link Band#passed()}
     * @param statedExplanation the judge's own sentence, and empty under a rubric that asked
     *                          for a bare number. Kept apart from {@code explanation} because
     *                          one is the model's and the other is assembled here
     */
    public record Result(String explanation, boolean passed, int score, Band band,
                         String statedExplanation) implements EvaluationResult {

        public Result {
            statedExplanation = statedExplanation == null ? "" : statedExplanation.strip();
        }
    }

    public Effect<Result> judge(JudgeRequest request) {
        if (request == null || request.transcript() == null || request.rubric() == null) {
            return effects().error("a transcript and a rubric are required");
        }

        return effects()
            .systemMessage(request.rubric().instructions())
            .memory(MemoryProvider.none())
            .userMessage(request.rubric().data(request.transcript()))
            .map(reply -> toResult(reply, request))
            .thenReply();
    }

    private static Result toResult(String reply, JudgeRequest request) {
        var parsed = Grade.read(
            request.transcript().scenarioName(), request.rubric(), reply);
        if (parsed.isEmpty()) {
            // Not a failing score — an absent one. Defaulting would fabricate a finding
            // and quietly move a campaign's band distribution. InconclusiveScore rather than a bare
            // runtime exception, so the runner files this as the judge declining and not as
            // a defect in the kit.
            throw new io.akka.evalkit.domain.InconclusiveScore(
                "judge returned no score for " + request.transcript().scenarioName()
                    + ": " + abbreviate(reply));
        }
        var grade = parsed.get();
        return new Result(
            grade.band() + " (" + grade.score() + "/10) under " + request.rubric().label(),
            grade.band().passed(), grade.score(), grade.band(), grade.explanation());
    }

    private static String abbreviate(String reply) {
        if (reply == null) return "<null>";
        return reply.length() <= 120 ? reply : reply.substring(0, 120) + "…";
    }

    /**
     * This judge, as the runner's {@link CampaignRunner.Judge}.
     *
     * <p>Here rather than in the runner because the runner is the half of this kit that
     * has no runtime: it schedules lanes, counts outcomes and decides what a campaign may
     * claim, none of which needs an SDK. Knowing about an Agent would have put one in the
     * dependency list of every project that only wanted to score a dataset.
     */
    public static CampaignRunner.Judge asJudge(
            java.util.function.BiFunction<Transcript, Rubric, Result> invoke) {
        return (transcript, rubric) -> {
            var result = invoke.apply(transcript, rubric);
            // The model's sentence, not the assembled one. Result.explanation restates the
            // band and the score for a trace to read, and a Grade carrying that would hold a
            // restatement of the two fields beside it where the judge's words belong.
            return new RunOutcome.Scored(Grade.of(
                transcript.scenarioName(), rubric, result.score(), result.statedExplanation()));
        };
    }
}
