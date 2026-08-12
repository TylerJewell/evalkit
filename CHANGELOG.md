# Changelog

Notable changes to evalkit. This project follows [semantic versioning](https://semver.org).

A rubric or metric version change is recorded here as well as in code, because
both change what a previously recorded score means.

## Unreleased

### Added

- `scenario-judge` v4, which asks for the judge's sentence under `EXPLANATION` where v3
  asks under `REASON`, so the label the model is given matches the field it parses into.
  v4 scores on v2's bands word for word, and `ReasonedGradeTest` pins that v4 differs
  from v3 in the label alone. v3 ships alongside it and both labels still read, because a
  rubric is held verbatim for as long as scores recorded under it are read back. Whether
  a judge writes differently under the two words is unmeasured until a calibration run.
- `ModelCall`, recording each call a run made to a model with the reasoning and the
  token counts it returned, and `Failure`, recording why a run ended without an
  answer. The two records are shaped after what an Akka interaction record carries, so
  a run this kit executed and a run read back from a ledger score on the same evidence.
- `ToolCall` carries an `id` and the tool's `response`.
- `Evidence` carries `systemMessage`, `modelCalls` and `failure` alongside the node,
  latency, tool calls and tokens it already held. `Evidence.thinking`,
  `Evidence.spend` and `Evidence.callsMissingUsage` read across the recorded calls.
- `RunOutcome.Failed`, separating a scorer that broke from a scorer that ran
  and declined. `InconclusiveScore` is how a scorer says it declined from inside a parse,
  where returning `Inconclusive` is not reachable.
- `RunSummary.Spend.over`, deriving the token totals and the unaccounted-call count
  from the observations instead of asking the target to report the count itself.
- `SdkContract`, recording the Akka SDK types this kit is shaped against and the
  commit they were read at, so a wrong assumption fails loudly once the jars exist.

- `scenario-judge` rubric v3, which asks for a score and one sentence on why, on
  bands worded exactly as v2 words them. v2 is unchanged and both are loadable, so
  a score recorded under v2 stays interpretable and `Scoring.compare` still
  refuses to read one against the other.
- `ModelReply`, which reads a labelled score and reason from a model's reply, and
  `Rubric.statesReason`, which decides from the prompt itself which reader applies.
- `ToolCorrectness` and `ArgumentCorrectness`, ported from DeepEval's action-layer
  agentic metrics.
- `AlignmentMetric` and the four trace-level metrics it carries: `TaskCompletion`,
  `StepEfficiency`, `PlanQuality` and `PlanAdherence`. Each one is a single model call
  returning a score and a reason, so each implements `Scorer`. A single score leaves
  `aggregate` no list of findings to turn into a number.
- `PortedMetrics.Pinning`, separating a port that reproduces upstream's expected
  values from one that cannot because upstream publishes none. Every unpinned
  entry states why, and `ConformanceCoverageTest` fails an entry that stays silent.

- `RunOutcome.Measured` for a result settled by computing a number against a
  threshold, with its own columns in `CampaignReport` and `RunSummary`.
- `Metric`, `Finding` and `MetricRef`, splitting a metric into finding
  collection and pure aggregation.
- `ToolPermission`, `TurnRelevancy`, `TurnFaithfulness` and
  `CitationFaithfulness`, ported from DeepEval with its own expected values.
- `Dag`, `DagNode` and `DagJudge`, a decision graph that calls a model only at
  branch points and keeps the score fixed in code.
- `Scorer` and `ScorerRouter`, routing each scenario to comparison, computation
  or a judge before any call goes out.
- `Observation`, `Evidence` and `ToolCall`, carrying what a run observed beyond
  the four fields a rubric interpolates.
- `Precursor.replay(String...)`, which the documentation already showed.
- A conformance suite pinning every ported metric to its upstream values, and a
  coverage test that fails when a ported metric has no fixture.

### Fixed

- `Grade.parseScore` read `"3.5"` as `3`. A fractional score is now
  unreadable, where it was truncated before. Reading it as 3 instead of 4 moved
  a run from the undecided band into the failing one, on a rounding choice the
  judge never made.

### Changed

- The vocabulary follows `akka.javasdk.evaluation` wherever the two name the same thing.
  `RunOutcome.Unscoreable` is `Inconclusive` and `RunOutcome.ScorerFailed` is `Failed`,
  matching `EvaluationRecord.Outcome`. The judge's 1-to-10 result is `Grade`, because
  the SDK spends `Verdict` on the outcome category. `Recording` is `Observation`, a
  letter further from the ledger's `InteractionRecord`. `Judgement` is `Finding` and its
  `subject` is `claim`, leaving `Subject` to the SDK's sealed type. `NoVerdict` is
  `InconclusiveScore`. `Band` stays, projected onto `Evaluation.label` at the SDK edge,
  and a score stays a 1-to-10 `int`, projected onto `Evaluation.score` as a `Double`.
- `explanation` says why the subject passed or failed, and `Grade` and `Measured` carry
  it; `reason` says why nothing was concluded, and `Inconclusive`, `Failed` and
  `NotReached` carry that. `statesReason` is `statesExplanation` throughout.
  `ScenarioJudge.Result.reason` is `statedExplanation`, since `EvaluationResult`
  already spends `explanation` on the assembled trace line.
- `RunSummary` renders the layout the README publishes. Both blocks open on the run's
  identity and close on what the kit cannot show, prose wraps at 78 columns against a
  72-column table, and the rules, the indent and the histogram bars beside each
  no-result cause are gone: the counts sit in the total column instead, so the four
  causes and the figure they sum to read down one edge. `RunSummary.results` takes an
  `Identity`, so a results block pasted on its own still names the run it describes.
- `PlanQuality` and `PlanAdherence` read the plan from the reasoning the run's model
  calls carried. Both metrics shipped returning `Inconclusive` on every run because
  nothing recorded a plan. Both now score a plan whenever the provider returned
  reasoning, and report the absence only when the provider returned none.
- `StepEfficiency` counts model calls as steps, so a run that called no tool is now
  scored on the calls it made.
- `ToolCorrectness` gained `comparingOutput`, which closes the divergence `NOTICE`
  recorded when a tool's return was not something a recording carried.
- A scorer that throws `InconclusiveScore` produces `Inconclusive`, and a scorer that throws
  anything else produces `Failed`. `CampaignReport` counts the two separately,
  and `notReachedOrUnscoreable` is now `withoutEvidence`, which covers every way a run
  can produce nothing.
- `Grade.rationale` is now `Grade.explanation` and holds the judge's own words.
  What it held before was assembled from the band and the score, which restated
  the two fields beside it; `RunOutcome.describe` builds that line where a report
  needs it. A grade from a rubric that asks for a bare number states no explanation.
- `RunOutcome.Measured` carries an `explanation`, empty for a metric that counts and
  filled by a metric whose score came from a model.
- `Finding` carries a `credit` between 0 and 1. The credit is 1 or 0 for a yes-or-no
  finding, which covers nearly all of them. `ToolCorrectness` needs the rest of the
  range, because upstream credits a call made with some of the right arguments.
- `Scorer.score` takes an `Observation` in place of a `Transcript`, so a metric can
  read tool calls and latency while a judge's input stays unchanged.
- `SystemUnderTest.Reply` carries latency and tool calls.
- `CampaignReport` gained `measured` and `measuredPassed`. State written before
  those columns existed reads back with them at zero, pinned by
  `WorkflowStateSerializationTest`.
