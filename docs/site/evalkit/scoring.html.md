<!-- <nav> -->
- [Akka](../../index.html)
- [Libraries](../index.html)
- [evalkit](index.html)
- [Scoring](scoring.html)

<!-- </nav> -->

# Scoring

## <a href="about:blank#_overview"></a> Overview

A scorer reads a finished `Recording` and returns a `RunOutcome`. Execution and scoring are
separate steps, so a rubric applies to a recording without running the conversation again,
and two services are compared under one rubric version.

A `Recording` holds a `Transcript` and an `Evidence`. The transcript carries the four fields
a rubric interpolates. The evidence carries what the run observed beyond them: the
specification node, the latency, the tool calls, the model calls, the instruction the model
was given, and the failure that ended the run. A metric reads the evidence. A judge reads
the transcript alone, so its input stays byte-identical whatever else the run observed.

Three scorer families exist, and each is defined by what settles the result.

| Family | Settles the result by | Model calls | Outcome |
|---|---|---|---|
| Comparison | The reply reached the specification node the scenario named. | 0 | `Asserted` |
| Computation | A metric scored the reply against a threshold. | 0 or more | `Measured` |
| Judgement | A judge read the transcript against a versioned rubric. | 1 or more | `Scored` |

Every scenario is routed before any call goes out. A scenario that names a specification node
is exercising a decision with a right answer, and sending it to a model buys a slightly
random opinion and pays for it. In one recorded dataset, 510 of 514 scenarios named a node.

## <a href="about:blank#_the_scorer_interface"></a> The scorer interface

[Scorer.java](https://github.com/tylerjewell/evalkit/blob/main/evalkit-core/src/main/java/io/akka/evalkit/domain/Scorer.java)
```java
public interface Scorer {

  RunOutcome score(Recording recording); // (1)

  default String id() { // (2)
    return getClass().getSimpleName();
  }
}
```

| **1** | The return type is `RunOutcome` so that a scorer which cannot produce a result says so. A `double` return type can express a low score and cannot express the absence of one, and the number it returns instead reaches someone's pass rate. |
| **2** | Names what produced the outcome, for the report. A judge returns its rubric label and a metric returns its metric label. |

`ScorerRouter.byExpectation` chooses the scorer for each scenario. A named node is left to
the runner, which compares it through `SpecNodeMatch`. A named metric routes to that metric.
A scenario naming neither states its expectation in prose, which only a judge reads.

## <a href="about:blank#_comparison"></a> Comparison

A comparison result is a pass or a fail. Comparison has no confidence to be borderline
about, so the undecided column carries a dash for this family. A figure in that cell would
report a defect in evalkit.

[SpecNodeMatch.java](https://github.com/tylerjewell/evalkit/blob/main/evalkit-core/src/main/java/io/akka/evalkit/domain/SpecNodeMatch.java)
```java
RunOutcome outcome = SpecNodeMatch.assertReached("GenUC-16a.3", reply.node()); // (1)
// Asserted[passed=false, expectedNode=GenUC-16a.3, actualNode=GenUC-17a]
```

| **1** | The target reports the node it answered from through `Reply.node()`. A target that reports no node produces a failing `Asserted` naming what was expected. |

## <a href="about:blank#_computation"></a> Computation

A metric computes a number over the recording and compares it against a threshold. The
arithmetic repeats exactly. The meaning is approximate, because the threshold is a judgment
encoded as a number.

`Metric` splits the work in two. Collecting judgements may call a model, and turning
judgements into a number is a pure function, so `aggregate` runs in a unit test with no
provider and no key.

[Metric.java](https://github.com/tylerjewell/evalkit/blob/main/evalkit-core/src/main/java/io/akka/evalkit/metric/Metric.java)
```java
var metric = ToolPermission.allowing("search_kb", "reply");
var judgements = metric.judge(List.of("search_kb", "delete_account"));

metric.aggregate(judgements);   // 0.5
metric.withinThreshold(0.5);    // false
```

The bundled metrics are `ToolPermission`, `ToolCorrectness`, `ArgumentCorrectness`,
`TurnRelevancy`, `TurnFaithfulness` and `CitationFaithfulness`. `Dag` expresses a rubric as
a decision graph and calls a model only at branch points.

`AlignmentMetric` covers the metrics whose score is one model call: `TaskCompletion`,
`StepEfficiency`, `PlanQuality` and `PlanAdherence`. An alignment metric implements `Scorer`,
because a single model-produced score gives `aggregate` nothing to work on.

A metric with nothing to read returns `Unscoreable`. `PlanQuality` needs the reasoning a
run's model calls carried, and a run whose provider returned none produces no score.

[RunOutcome.java](https://github.com/tylerjewell/evalkit/blob/main/evalkit-core/src/main/java/io/akka/evalkit/domain/RunOutcome.java)
```java
record Measured(String metricId, int metricVersion,
                double value, double threshold, boolean withinThreshold,
                String reason)
  implements RunOutcome {}
```

The reason is empty for a metric that counts, and carries a sentence for a metric whose
score came from a model.

|  | A tuned threshold moves a score without the service changing. One dataset in this project's history scored 23, 17 and 19 out of 40 across three passes at a text-extraction heuristic while the service never changed. `Measured` carries a metric id and version for that reason, and `Scoring.compare` refuses to compare results produced under different metric versions. |

## <a href="about:blank#_judgement"></a> Judgement

A judge sends the transcript to a model with a versioned rubric. This family is the only one
that always costs money.

### <a href="about:blank#_rubrics_are_versioned_data"></a> Rubrics are versioned data

[Rubric.java](https://github.com/tylerjewell/evalkit/blob/main/evalkit-core/src/main/java/io/akka/evalkit/domain/Rubric.java)
```java
var rubric = Rubric.load("scenario-judge", 3); // (1)

var verdict = Verdict.read("refund-30d", rubric, reply).orElseThrow(); // (2)
```

| **1** | Loads `rubrics/scenario-judge-v3.txt` from the classpath. v2 asks for a value from 1 to 10 and nothing else. v3 asks for that value and one sentence stating what decided it, on bands worded exactly as v2 words them. |
| **2** | Every verdict carries the rubric id and version that produced it. `Rubric.statesReason` decides which reader applies, so a reply to v3 that lost its label is unreadable instead of being read as a bare number. |

`Scoring.compare` throws when two verdict sets carry different rubric versions. Scoring a
baseline under v2 and a candidate under v3 attributes a change in the rubric to the service.
Re-score the kept transcripts under the newer rubric instead, which costs judge calls and no
conversations.

### <a href="about:blank#_bands_carry_the_result"></a> Bands carry the result

`Scoring` works in bands and reports movement between them. Identical runs of one dataset
scored 7, 8, 9 and 10 on the same as-specified scenarios, so an average of the raw scores
describes the judge. The middle band counts as undecided, because measured agreement with an
independent reviewer is 89 to 91 percent on clear-cut replies and 53 percent on borderline
ones.

## <a href="about:blank#_runs_that_produced_nothing"></a> Runs that produced nothing

A run can end with nothing to report about the service. The outcome says which way that
happened, and none of these three enters a pass rate.

| Outcome | Produced by |
|---|---|
| `NotReached` | The precursor never landed, or the service answered nothing. |
| `Unscoreable` | A scorer ran and reached no verdict, or threw `NoVerdict`. |
| `ScorerFailed` | A scorer threw anything else, which is a defect in evalkit. |

A content filter refused to score one transcript during calibration, which is the recorded
case for `Unscoreable`. That refusal reaches the runner as a thrown exception several frames
inside parsing a reply. `NoVerdict` lets the throwing code name which of the two happened,
because the runner cannot tell them apart from the stack.

## <a href="about:blank#_see_also"></a> See also

- [evalkit overview](index.html)
- [Testing with the Akka TestKit](testing.html)
- [LLM evaluation in the Akka SDK](../../sdk/agents/llm_eval.html)
