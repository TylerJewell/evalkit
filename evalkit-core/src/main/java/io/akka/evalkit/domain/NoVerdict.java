package io.akka.evalkit.domain;

/**
 * Thrown by a scorer that ran and reached no verdict.
 *
 * <p>A scorer signals absence two ways. It can return {@link RunOutcome.Unscoreable}, which
 * is what a metric with nothing to measure does. Or it can throw this, which is what a judge
 * does when it is several frames deep in parsing a reply and there is no score in it.
 *
 * <p><b>Why it is a separate type.</b> Every other exception reaching the runner is a defect
 * in this kit and becomes {@link RunOutcome.ScorerFailed}. Both arrive as a throw, so the
 * runner cannot tell them apart from the stack alone &mdash; only the code that threw knows
 * whether it declined or broke, which is the same reason {@link RunOutcome.Cause} is decided
 * where the failure happened rather than parsed afterwards.
 *
 * <p>The case this exists for is recorded: during calibration a content filter refused to
 * score an identification-failure transcript. That is absent evidence about the transcript,
 * not a bug in the harness, and a report that files it under harness defects understates the
 * dataset exactly as much as filing a harness defect under refusals overstates it.
 */
public class NoVerdict extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NoVerdict(String reason) {
        super(reason);
    }

    public NoVerdict(String reason, Throwable cause) {
        super(reason, cause);
    }
}
