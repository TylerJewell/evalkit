# Evaluator and InteractionRecord as evalkit's core abstractions

This document is a proposal, and nothing in it is implemented.

## What this proposes

`akka.javasdk.evaluation.Evaluator` becomes the way a scorer is packaged and triggered, and
`akka.javasdk.ledger.InteractionRecord` becomes the evidence every scorer reads.
`evalkit-core` takes a dependency on `akka-javasdk`, and the evalkit records that mirror SDK
types are deleted. `Verdict`, `RunOutcome` and `Band` stay, because each one holds an
invariant no SDK type holds. The SDK ships no counterpart for `SystemUnderTest`,
`CampaignPlan` or `CampaignReport`. This proposal leaves `SystemUnderTest`, `CampaignPlan`
and `CampaignReport` unchanged.

## The SDK surface

Read from `akka-javasdk-3.6.0-59-7321c44b-dev-SNAPSHOT-sources.jar`, branch
`feature/governance`, commit `7321c44b`.

The sources jar carries main sources alone. The branch's own tests answer several questions
the jar leaves open, so the behaviour below is also read from `akka-javasdk-testkit` and
from `akka-javasdk-tests/src/test/java/akkajavasdk/components/evaluation`, at the same
commit. A claim sourced from a test names the test.

`Evaluator` is an abstract class with one abstract method:

```java
public abstract Effect evaluate(EvaluationContext context);
```

`Effect.Builder` offers four terminal calls: `complete(Evaluation, Evaluation...)`,
`complete(List<Evaluation>)`, `inconclusive(String reason)` and
`asyncEffect(CompletionStage<Effect>)`.

`EvaluationContext` carries two accessors, `subject()` and `evaluationId()`. `Subject` is a
sealed interface over `FlowInteraction(flowId, agentComponentId, interactionId)` and
`AgentInteraction(agentComponentId, interactionId)`. An `Evaluator` receives identifiers and
no content.

`LedgerClient` turns an identifier into content through `getInteraction(String)`,
`getInteractionAsync(String)`, `getEvaluation(String)` and `getEvaluationAsync(String)`.
`SdkRunner` lists `LedgerClient` in `platformManagedDependency`, so a component declares it
as a constructor parameter and the runtime supplies it.

An interaction is one agent turn. `InteractionRecord` documents itself as the record of a
single interaction, `sessionId` names the conversation above it, `inputMessage` is one
message rendered as a list of content parts, and `modelResponses` is the tool-calling loop
that answered it rather than a series of turns.

`InteractionRecord` holds twelve components: `interactionId`, `sessionId`,
`agentComponentId`, `flowId`, `metadata`, `systemMessage`, `inputMessage`, `modelResponses`,
`toolCallResponses`, `taskContext`, `failure` and `timestamp`. `InteractionRecord` derives `inputText()`,
`finalResponseText()`, `toolCalls()`, `totalInputTokens()`, `totalOutputTokens()`,
`failureSummary()` and `transcript()`.

`Evaluation` holds `passed` as a boolean, `explanation` as a string, `score` as an
`Optional<Double>`, `label` as an `Optional<String>` and `attributes` as a
`Map<String, String>`.

`EvaluationRecord.Outcome` is a sealed interface over `Verdict(List<Evaluation>)`,
`Inconclusive(String reason)` and `Failed(String reason)`. `EvaluationRecord.Trigger` names
`UNSPECIFIED`, `MANUAL` and `ON_INTERACTION`.

An evaluator binds to an agent through configuration.
`EvaluatorSettings` reads `akka.javasdk.evaluation.evaluators.<evaluatorId>.agents.<agentId>`
and requires a `trigger` key on each binding. The single accepted trigger value is
`interaction`. Defaults fall back to `akka.javasdk.evaluation.defaults.evaluator` and
`akka.javasdk.evaluation.defaults.agent`.

`WorkflowEvaluator<S>` is a separate component type with `onEvaluation(EvaluationContext)`,
a state parameter, `transitionTo` effects, and a `Settings` object carrying
`evaluationTimeout`, `defaultStepTimeout` and `maxStepRetries`.

`ComponentLocator` registers `ComponentType.Evaluator` and `ComponentType.WorkflowEvaluator`
as distinct located component types. `ComponentLocator` also ships `ToxicityEvaluator`,
`SummarizationEvaluator` and `HallucinationEvaluator` as evaluator agents.
`ToxicityEvaluator`, `SummarizationEvaluator` and `HallucinationEvaluator` extend
`LlmAsJudge`, carry `@AgentRole("evaluator")`, and return a record implementing
`EvaluationResult`.

## What evalkit runs today

`evalkit-core` holds 45 main sources, 17 of them under `metric`. `evalkit-akka` holds two
main sources.

`ScenarioJudge` extends `Agent`, carries `@AgentRole("evaluator")`, and its `Result` record
implements `akka.javasdk.agent.EvaluationResult`. `HallucinationEvaluator` extends `LlmAsJudge`, carries
`@AgentRole("evaluator")`, and returns a record implementing `EvaluationResult`. `CampaignWorkflow` extends `akka.javasdk.workflow.Workflow`.

No source in either module references `akka.javasdk.evaluation` or `akka.javasdk.ledger`.
`SdkContract` records 14 SDK types and names an evalkit counterpart for six of them.

## Scorer maps onto Evaluator without a rewrite

`Scorer` is a functional interface returning `RunOutcome`. Every `RunOutcome` variant has a
terminal call on `Effect.Builder`:

| `RunOutcome` variant | `Effect` call | Resulting `EvaluationRecord.Outcome` |
|---|---|---|
| `Scored(Verdict)` | `complete(Evaluation)` | `Verdict` |
| `Asserted(passed, expectedNode, actualNode)` | `complete(Evaluation)` | `Verdict` |
| `Measured(metricId, version, value, threshold, withinThreshold, reason)` | `complete(Evaluation)` | `Verdict` |
| `Unscoreable(reason)` | `inconclusive(reason)` | `Inconclusive` |
| `ScorerFailed(reason)` | no builder call | `Failed`, on a thrown exception |
| `NotReached(cause, reason, precursor)` | no builder call | none |

`Unscoreable` and `inconclusive` carry the same fact and the same payload. The separation of
a declining scorer from a broken one, recorded in `design-history.md` under "What each
outcome records", survives the mapping for five of the six variants.

`Verdict` maps onto `Evaluation` field for field:

| `Verdict` | `Evaluation` |
|---|---|
| `band().passed()` | `passed` |
| assembled band, score and rubric | `explanation` |
| `score()` widened to `double` | `score` |
| `band().name()` | `label` |
| `scenarioName`, `rubricId`, `rubricVersion` | `attributes` |

`Measured` maps the same way, with `metricId`, `metricVersion`, `value` and `threshold`
landing in `attributes`.

## Evidence and Transcript map onto InteractionRecord

The six evalkit records that `SdkContract` already names have direct sources:

| evalkit | `InteractionRecord` source |
|---|---|
| `Transcript.systemOutput` | `finalResponseText()` |
| `Transcript.simulationHistory` | `transcript()` |
| `Evidence.modelCalls` | `modelResponses` |
| `Evidence.toolsCalled` | `toolCalls()` |
| `Evidence.systemMessage` | `systemMessage` |
| `Evidence.failure` | `failure` |
| `Evidence.tokens` | `totalInputTokens()` and `totalOutputTokens()` |
| `ModelCall.thinking` | `ModelResponse.thinking` |

`Transcript.replayHistory` and `Transcript.expectedOutcome` have no source in an
`InteractionRecord`. The scenario supplies both fields.

## Campaign control flow has no Evaluator counterpart

An `Evaluator` reads two strings from its `EvaluationContext` and fetches one
`InteractionRecord`. The `Evaluator` receives no scenario, no expected outcome and no
precursor.

evalkit runs arrange, act and score in `ScenarioRunner.execute`. `ScenarioRunner.execute` calls
`SystemUnderTest.prepare(Precursor)` and then `SystemUnderTest.submit`. An `Evaluator` runs
after an interaction the platform already recorded, so it performs the score step alone.

`RunOutcome.NotReached` records a precursor that never landed. No interaction exists in that
case, so no evaluator is triggered and no `EvaluationRecord` is written. `NotReached` stays
a campaign-only outcome, and the ledger cannot represent it.

## What the coupling adds

Reference-free metrics score production traffic. `TurnRelevancy`, `TurnFaithfulness`,
`ToolPermission`, `StepEfficiency` and `CitationFaithfulness` read the interaction and never
read an expected outcome. Bound with `trigger = interaction`, each one scores live agent
traffic. evalkit today scores only interactions it caused.

A scored run becomes durable without evalkit storing it. `EvaluationRecord` holds the
outcome, the evaluator component id, the trigger, the interaction id and the timestamp.

A recorded interaction can be re-scored on a new rubric. `LedgerClient.getInteraction`
returns the record, and `Scoring.compare` already refuses to compare verdicts from different
rubrics.

## Fidelity risks

**`Evaluation.passed` is a boolean and `Band` has a middle band.** `Band.needsReview()`
marks the band that `CampaignReport` counts as undecided. A reader who consumes `passed()`
alone sees that band as a pass or a fail. `design-history.md` records the measured agreement
behind the middle band as 53 percent on borderline replies. Carrying the band in `label` and
reading it back in evalkit's report keeps the third state.

**`Evaluation.score` is an unbounded `Optional<Double>` and `Verdict.score` is an integer
from 1 to 10 with a `Band` invariant.** A `Verdict` constructed from a ledger score outside
that range throws. The adapter reading an `EvaluationRecord` back into a `Verdict` returns
`Unscoreable` on an out-of-range score.

**`Effect.Builder` has no `failed` call, and `EvaluationRecord.Outcome.Failed` exists.** The
path from a broken scorer to a `Failed` outcome runs through a thrown exception, and the
branch's own tests hold both ends of it. `EvaluatorIntegrationTest.recordsFailedOutcomeWhenTheJudgeFails`
makes the judge call throw and asserts the recorded outcome is `Failed` carrying no
evaluations; `EvaluatorIntegrationTest.recordsInconclusiveOutcome` returns
`effects().inconclusive(reason)` and asserts `Inconclusive` carrying that reason. evalkit's
distinction survives the boundary: `Unscoreable` maps onto `inconclusive`, and
`ScorerFailed` onto a throw.

**`EvaluationRecord` flattens deterministic and judged results into one
`List<Evaluation>`.** `CampaignReport` prints them in separate columns and prints a dash in
the deterministic undecided cell. An `attributes` key naming the outcome variant keeps the
columns separable.

**SDK `ToolCall.arguments` is a `String` and evalkit `ToolCall.arguments` is a
`Map<String, String>`.** Adopting the SDK record makes a parse mandatory, and the section
"Parsing tool arguments" below states the handling.

**`SdkContract` names no entry for `ToolCallResponse` or `MessageContent`.**
`InteractionRecord.toolCallResponses` is a `List<ToolCallResponse>` and
`InteractionRecord.inputMessage` is a `List<MessageContent>`. `ToolCallResponse` and
`MessageContent` are unrecorded assumptions today.

## The module boundary

`CLAUDE.md` states that adding a dependency to `evalkit-core` requires an argument.
`InteractionRecord` and `Evaluator` are `akka.javasdk` types.

**The decision is that `evalkit-core` depends on `akka-javasdk`.** Scorers read
`InteractionRecord` directly and the mirrored records are deleted.

`evalkit-core` no longer builds where the Akka repository is unavailable, so the `core` job
in `.github/workflows/build.yml` needs the `AKKA_MAVEN_REPO_URL` gate the `akka` job already
carries. The `core` job's steps asserting an empty compile classpath are deleted, along with
the fixture proving the pattern catches a real dependency. A pull request from a fork builds neither module until the SDK
ships. The module-boundary section of `CLAUDE.md` is rewritten in the same change.

A service written in another language is still evaluated by implementing `SystemUnderTest`,
and the adapter that implements it is Java. What the dependency ends is the property that
`evalkit-core` compiles with nothing on its classpath.

## Which types the SDK supplies and which evalkit keeps

A type comes from the SDK when it carries evidence. A type stays in evalkit when it holds an
invariant the SDK does not enforce.

| evalkit type today | Under this proposal |
|---|---|
| `Evidence` | deleted, and `Recording` holds an `InteractionRecord` |
| `ModelCall` | deleted, and `ModelResponse` replaces it |
| `ToolCall` | deleted, and `akka.javasdk.ledger.ToolCall` replaces it |
| `Failure` | deleted, and `akka.javasdk.ledger.Failure` replaces it |
| `Transcript` | kept for `replayHistory` and `expectedOutcome` |
| `Verdict` | kept for the 1-to-10 score and the `Band` invariant |
| `Band` | kept, and `Evaluation` has no third state |
| `RunOutcome` | kept, and `NotReached` has no ledger counterpart |
| `SdkContract` | deleted |

`SdkContract` records assumptions about types `evalkit-core` cannot see. Once core compiles
against the jars, the compiler checks every assumption `SdkContract` lists. `SdkContractTest`
and `SdkContractReflectionTest` are deleted with it.

`Evidence` holds two components no `InteractionRecord` carries. `Evidence.node` names the
specification node an answer came from. `Evidence.latency` times the graded turn, and
`InteractionMetadata` supplies `callStartedAt` and `callFinishedAt`. `Recording` keeps `node`
and derives `latency` from the metadata.

## Where a stated reason lands

`Verdict.reason` holds the judge's own sentence under rubric v3 as a string.
`Judgement.reason` holds why a metric affirmed or denied a subject. `RunOutcome.Measured.reason`
holds the same for a measured outcome.

`Evaluation.explanation` is a string and takes `RunOutcome.describe()`, which renders the
band, the score and the judge's sentence in one line. `Evaluation.attributes` takes
`scenarioName`, `rubricId`, `rubricVersion`, `metricId`, `metricVersion` and `threshold`.
`Evaluation.attributes` carries no reason.

`design-history.md` records a transcript scored 10 and `FAITHFUL` under both rubrics, where
the v3 reason claimed a refusal the transcript does not contain. The reason travels in
`explanation` so a ledger reader can check that claim against the interaction.

## Parsing tool arguments

`ToolCorrectness.compare` awards credit as the share of keys named by either side that both
sides name with the same value. A call carrying three of four expected arguments scores above
a call carrying none. SDK `ToolCall.arguments` is a string, and a string has no keys.

Adopting the SDK record adds `Arguments.parse(String)` returning a `Map<String, String>`.
Jackson arrives on the classpath with `akka-javasdk`, so the parse reads JSON.

A call whose arguments do not parse produces `Unscoreable`. A zero would report a wrong call,
and the failure is in the parse.

Tests this change needs: a case asserting that a call with unparseable arguments returns
`Unscoreable`, and a case asserting that a call carrying three of four expected arguments
scores above a call carrying none.

## Proposed design

### Phase 1 — core takes the dependency

Add `akka-javasdk` to `evalkit-core/pom.xml`. Gate the `core` job on `AKKA_MAVEN_REPO_URL`
and delete the steps asserting an empty compile classpath. Rewrite the module-boundary
section of `CLAUDE.md` and the Build section of the README.

Tests this phase needs: `mvn -B install` from the aggregator root, which has never run green.

### Phase 2 — Recording holds an InteractionRecord

Change `Recording` to `Recording(Transcript transcript, InteractionRecord interaction,
Optional<String> node)`. Delete `Evidence`, `ModelCall`, `ToolCall` and `Failure`. Repoint
the 17 metric sources at the record.

`PlanQuality` and `PlanAdherence` read `ModelResponse.thinking`. `ToolCorrectness` and
`ArgumentCorrectness` read `InteractionRecord.toolCalls()`. `CampaignReport` derives its
unaccounted-call count from `totalInputTokens()` and `totalOutputTokens()`.

Tests this phase needs: a case per metric asserting the value came from the record, and a
case asserting that a record with no `modelResponses` produces `Unscoreable` from
`PlanQuality`. A metric that scores an empty record proves nothing, and `design-history.md`
records an empty search space passing an audit twice.

### Phase 3 — Scorer as Evaluator

Add `io.akka.evalkit.evaluation.ScorerEvaluator` to `evalkit-core`:

```java
public abstract class ScorerEvaluator extends Evaluator {
    protected ScorerEvaluator(LedgerClient ledger) { ... }
    protected abstract Scorer scorer();
    protected abstract String expectedOutcome(InteractionRecord record);

    @Override
    public Effect evaluate(EvaluationContext context) { ... }
}
```

`evaluate` fetches the interaction, builds a `Recording`, runs the scorer, and switches on
the `RunOutcome` with no `default` branch. `Unscoreable` calls `inconclusive`.
`ScorerFailed` rethrows.

Tests this phase needs: a test per `RunOutcome` variant asserting the terminal call, and a
test asserting that a scorer throwing `NoVerdict` with the declining reason produces
`inconclusive`.

### Phase 4 — the reference-free metric evaluators

Ship one `Evaluator` subclass per metric that reads no expected outcome. `TurnRelevancy`,
`TurnFaithfulness`, `ToolPermission`, `StepEfficiency` and `CitationFaithfulness` qualify.
Each one carries a `@Component` id that a user names in
`akka.javasdk.evaluation.evaluators`.

Tests this phase needs: a test asserting that a metric reading an interaction with no tool
call returns `Unscoreable`. `design-history.md` records that DeepEval scores an interaction
with no tool call 1 and passes it.

### Phase 5 — reading the ledger back into a report

Add a `SystemUnderTest` implementation that reads recorded interactions through
`LedgerClient` and causes none. `CampaignReport` then covers runs evalkit did not execute.

Tests this phase needs: a test asserting that a `Verdict` built from an `Evaluation` with a
score outside 1 to 10 returns `Unscoreable`.

### Phase 6 — CampaignWorkflow against WorkflowEvaluator

`WorkflowEvaluator<S>` carries state, step transitions, and a `Settings` object holding the
evaluation timeout, the step timeout and the retry ceiling. `CampaignWorkflow` holds a
cursor and writes it after each wave. Whether `CampaignWorkflow` maps onto
`WorkflowEvaluator`, and whether a wave maps onto the flow a `Subject.FlowInteraction`
names, are both unanswered. Phase 6 is scoped as a comparison.

`WorkflowEvaluatorIntegrationTest.runsMultiStepEvaluationForBoundAgentInteraction` is the
worked example to compare against.

## Configuration a user writes

```hocon
akka.javasdk.evaluation.evaluators {
  turn-relevancy {
    enabled = true
    agents {
      my-agent { enabled = true, trigger = interaction }
    }
  }
}
```

`CampaignPlan.check` refuses a campaign before it runs. An evaluator named in configuration
with no matching component id is the same class of failure. Extending `check` to read the
evaluator bindings is in scope for Phase 4.

## What the branch's tests settle

**A broken evaluator and a declining one are distinguishable.** Stated under Fidelity risks
above, from `EvaluatorIntegrationTest`.

**`EvaluationContext.evaluationId` is assigned before `evaluate` runs, and the ledger
returns a record for it once the evaluation terminates.** `ResponseQualityEvaluator` records
`context.evaluationId()` as its first statement, before any call that can fail, and
`EvaluatorIntegrationTest.evaluationFor` then fetches that id through
`LedgerClient.getEvaluation` and asserts the outcome on what comes back. Phase 5 has its
round-trip.

**The runtime, not the evaluator, decides which `Subject` variant arrives.**
`EvaluatorImpl.scala:46-48` returns `FlowInteraction` when the SPI subject carries a flow and
`AgentInteraction` otherwise, and `WorkflowEvaluatorProtocol.java:42-43` keys the same choice
on the presence of a `flowId`. No SDK API sets a `flowId`; it arrives from below the SDK.

## Open questions for the SDK team

1. **`Trigger.MANUAL` is mapped from the SPI but no SDK API produces it.** `ComponentClient`
   exposes eight entry points at this commit and none is an evaluator;
   `akka.javasdk.client` holds no `EvaluatorClient`. The only direct invocation on the branch
   is `EvaluatorTestKit.evaluate(Subject)`, which ships in the testkit and is test-only. Is a
   caller-side trigger planned, and is `EvaluatorTestKit` the intended shape for it? evalkit's
   campaigns are that caller.
2. **Can a binding be narrowed below the agent, so that a campaign's setup turns are not
   scored?** An interaction is one agent turn, and a binding names an agent and nothing else:
   `EvaluatorSettings` reads `enabled` and `trigger` per agent, with no session filter and no
   predicate over the interaction. So every turn a bound agent takes fires the evaluator.

   evalkit does not grade every turn. A scenario starts part-way through a conversation, and
   `Precursor.replay` reaches that point by walking the conversation — turns the campaign
   sends only to arrange the state it wants to examine. Those are interactions like any other.
   Bound as configuration allows today, a five-turn precursor scores five times and the graded
   turn once, and the five carry a rubric written for a question they were never asked.

   Three consequences, in the order they hurt. The count breaks first: `CampaignReport` holds
   one outcome per scenario, and a scenario that emits six `Evaluation`s has no row. Then the
   result is wrong rather than merely miscounted, because a setup turn judged against the
   graded turn's rubric fails, and a failure that says nothing about the system is the exact
   thing this kit exists to keep out of a report. Cost is last and smallest: judged scoring is
   a model call, so spend scales with conversation length and `RunSummary.Estimate`
   understates it by that factor.

   The question is therefore whether a narrowing exists or is planned — a metadata key, a
   session-level opt-out, an interaction-level suppression — or whether the answer is that a
   campaign must bind no evaluator and call its scorers directly, which is what evalkit does
   today. `InteractionMetadata` carries model configuration, timing and a finish reason, and
   nothing that distinguishes an interaction sent to arrange a state from one sent to be
   judged.
3. **What makes an interaction a flow interaction upstream?** The runtime picks the variant, so
   an evaluator must handle both, and no integration test exercises a runtime-produced
   `FlowInteraction` — only `SimpleEvaluatorTest.worksWithFlowInteractionSubject`, which
   constructs one by hand through the testkit. Whether a campaign wave maps onto a flow is
   asked with the `WorkflowEvaluator` comparison in Phase 6.
