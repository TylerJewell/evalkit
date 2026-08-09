<!-- <nav> -->
- [Akka](../../index.html)
- [Libraries](../index.html)
- [evalkit](index.html)
- [Scoring](scoring.html)

<!-- </nav> -->

# Scoring

## <a href="about:blank#_overview"></a> Overview

A scorer reads a finished `Transcript` and returns a `RunOutcome`. Execution and scoring are
separate steps, so a rubric applies to a recording without running the conversation
again and two services are compared under one rubric version.

Three scorer families exist, and each is defined by what settles the result.

| Family | Settles the result by | Model calls | Outcome |
|---|---|---|---|
| `deterministic` | Comparison against a stated expectation. | 0 | `Asserted` |
| `heuristic` | Computation over the reply against a threshold. | 0 | `Measured` |
| `agentic` | A judge agent reading the transcript against a versioned rubric. | 1 or more | `Scored` or `Unscoreable` |

Every scenario is routed before any call goes out. A scenario that names a specification node is
exercising a decision with a right answer, and sending it to a model buys a slightly random
opinion and pays for it. In one recorded corpus, 510 of 514 scenarios named a node.

## <a href="about:blank#_the_scorer_interface"></a> The scorer interface

[Scorer.java](https://github.com/tylerjewell/evalkit/blob/main/evalkit-core/src/main/java/io/akka/evalkit/scorer/Scorer.java)
```java
public interface Scorer {

  RunOutcome score(Transcript transcript); // (1)

  String id(); // (2)
}
```

| **1** | The return type is `RunOutcome` so that a scorer which cannot produce a result says so. A `double` return type can express a low score and cannot express the absence of one, and the number it returns instead reaches someone's pass rate. |
| **2** | Names what produced the outcome, for the report. A judge returns its rubric label, a heuristic returns its metric label. |

A `ScorerRouter` chooses the scorer for each scenario. The bundled implementation reads the
scenario: a named node routes to `NodeMatch`, a declared metric routes to that metric, and
everything else routes to the judge.

## <a href="about:blank#_deterministic_scorers"></a> Deterministic scorers

A deterministic result is a pass or a fail. Comparison has no confidence to be borderline
about, so the undecided column carries a dash for this family. A figure in that cell would
report a defect in evalkit.

[NodeMatch.java](https://github.com/tylerjewell/evalkit/blob/main/evalkit-core/src/main/java/io/akka/evalkit/scorer/deterministic/NodeMatch.java)
```java
var scorer = NodeMatch.expecting("GenUC-16a.3"); // (1)

RunOutcome outcome = scorer.score(transcript);
// Asserted[passed=false, expected=GenUC-16a.3, actual=GenUC-17a]
```

| **1** | The target reports the node it answered from through `Reply.node()`. A target that reports no node produces a failing `Asserted` naming what was expected. |

`ExactMatch`, `JsonSchemaMatch` and `ToolCallMatch` complete the family. `ToolCallMatch`
asserts that a named tool was invoked with given arguments, which is the agentic case that
needs no model to settle.

## <a href="about:blank#_heuristic_scorers"></a> Heuristic scorers

A heuristic scorer computes a number over the reply and compares it against a threshold.
Execution repeats exactly. The meaning is approximate, because the threshold is a judgment
encoded as a number.

[Measured.java](https://github.com/tylerjewell/evalkit/blob/main/evalkit-core/src/main/java/io/akka/evalkit/scorer/heuristic/Measured.java)
```java
record Measured(String metricId, int metricVersion,
                double value, double threshold, boolean withinThreshold)
  implements RunOutcome {}
```

|  | A tuned threshold moves a score without the service changing. One corpus in this project's history scored 23, 17 and 19 out of 40 across three passes at a text-extraction heuristic while the service never changed. `Measured` carries a metric id and version for that reason, and `Scoring.compare` refuses to compare results produced under different metric versions. |

`LatencyBudget` and `TokenBudget` are the two heuristics worth running on every campaign,
because both measure a property of the service that carries no interpretation.

## <a href="about:blank#_agentic_scorers"></a> Agentic scorers

An agentic scorer sends the transcript to a model with a versioned rubric. The agentic
family is the only one that costs money and the only one that can return `Unscoreable`.

### <a href="about:blank#_rubrics_are_versioned_data"></a> Rubrics are versioned data

[Rubric.java](https://github.com/tylerjewell/evalkit/blob/main/evalkit-core/src/main/java/io/akka/evalkit/scorer/agentic/Rubric.java)
```java
var rubric = Rubric.load("scenario-judge", 2); // (1)

var verdict = Verdict.of(transcript.scenarioName(), rubric, 8, ""); // (2)
```

| **1** | Loads `rubrics/scenario-judge-v2.txt` from the classpath. `evalkit-akka` adds a source backed by a [PromptTemplate entity](../../sdk/agents/prompt.html), so a rubric can be updated on a running service. |
| **2** | Every verdict carries the rubric id and version that produced it. |

`Scoring.compare` throws when two verdict sets carry different rubric versions. Scoring a
baseline under v2 and a candidate under v3 attributes a change in the rubric to the service. Re-score the kept transcripts under the newer rubric instead, which costs
judge calls and no conversations.

### <a href="about:blank#_bands_carry_the_result"></a> Bands carry the result

`Scoring` works in bands and reports movement between them. Identical runs of one corpus
scored 7, 8, 9 and 10 on the same as-specified scenarios, so an average of the raw scores
describes the judge. The middle band counts as undecided, because measured agreement with an
independent reviewer is 89 to 91 percent on clear-cut replies and 53 percent on borderline
ones.

### <a href="about:blank#_judge_panels"></a> Judge panels

A panel scores one transcript with several judges and reports the shape of the
disagreement.

In `evalkit-akka` the judges run as delegated workers of an
[Autonomous Agent](../../sdk/autonomous-agents.html). `Delegation` partitions context, so a
worker scores without reading another worker's verdict.

[JudgePanel.java](https://github.com/tylerjewell/evalkit/blob/main/evalkit-akka/src/main/java/io/akka/evalkit/akka/JudgePanel.java)
```java
@Component(id = "judge-panel",
    description = "Produces independent verdicts on one transcript.")
public class JudgePanel extends AutonomousAgent {

  @Override
  public AgentDefinition definition() {
    return define()
      .capability(TaskAcceptance.of(JudgeTasks.SCORE).maxIterationsPerTask(3)) // (1)
      .capability(Delegation
          .to(FaithfulnessJudge.class, GroundingJudge.class, ToneJudge.class)
          .maxParallelWorkers(3)); // (2)
  }
}
```

| **1** | The iteration limit terminates a task the model neither completes nor abandons. |
| **2** | Each delegated judge is a request-based `Agent` scoring the transcript in one round-trip. |

|  | `Moderation` also runs several agents over one input, and a participant receives the conversation entries added since its last turn. A judge that reads another judge's verdict cannot contribute to an agreement figure. Use `Delegation` to measure agreement and `Moderation` for a refutation pass over a verdict already recorded. |

A panel whose judges agree returns `Scored` and records the agreement in the report. A panel
whose judges disagree returns `Disputed`, which holds every verdict and counts as undecided.
A panel that cannot reach its quorum, because judges timed out or refused, returns
`Unscoreable` on the same terms as a single judge.

|  | `Disputed` is conflicting evidence and stays out of the pass-rate denominator. Averaging the panel would replace an observed disagreement with a number that hides it. |

### <a href="about:blank#_calibration_gates_a_campaign"></a> Calibration gates a campaign

A judge is a measuring instrument and drifts. `Calibration` records agreement against a
held-out set labelled by a person.

```java
record Calibration(String rubricId, int rubricVersion, int samples,
                   double clearCutAgreement, double borderlineAgreement,
                   Instant measuredAt) {}
```

`CampaignPlan.check` refuses a campaign whose judge calibration is absent, older than a
stated age, or below a stated agreement. A campaign that runs states what its judge was
worth on the day it ran.

## <a href="about:blank#_built_in_evaluators"></a> Built-in Akka evaluators

`evalkit-akka` adapts the evaluators that ship with the Akka SDK, so
[ToxicityEvaluator, SummarizationEvaluator and HallucinationEvaluator](../../sdk/agents/llm_eval.html#_built_in_evaluators)
are usable as scorers without a rubric of your own.

```java
var scorer = AkkaEvaluator.of(componentClient, ToxicityEvaluator.class);
```

The evaluator returns an `EvaluationResult`, which the Akka runtime captures into metrics
and traces. `AkkaEvaluator` maps a passing result to `Scored` and a thrown call to
`Unscoreable`.

## <a href="about:blank#_see_also"></a> See also

- [Scenarios and corpora](scenarios.html)
- [Reports](reports.html)
- [LLM evaluation in the Akka SDK](../../sdk/agents/llm_eval.html)
