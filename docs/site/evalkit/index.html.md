<!-- <nav> -->
- [Akka](../../index.html)
- [Libraries](../index.html)
- [evalkit](index.html)

<!-- </nav> -->

# evalkit

## <a href="about:blank#_overview"></a> Overview

evalkit measures whether a conversational service answered correctly, and reports which
numbers the result supports. A campaign puts the service into a known state, says the
graded turn to it, records what came back, scores the observation, and prints a report of the
result.

An evaluation result is a number that reaches people who cannot check it. Every failure
mode of an evaluation is silent: a campaign that ran half its scenarios, an audit that
examined nothing, a judge scoring a pure function, a score that measures the tooling. The
design of this library treats each of those as a defect to be made visible.

### <a href="about:blank#_when_to_use_evalkit"></a> When to use evalkit

evalkit covers these tasks:

- Score a conversational service against recorded scenarios and report the result to a
  compliance, risk, or engineering audience.
- Compare two versions of a service under one rubric version. `Scoring.compare` throws when
  the two sides were scored under different rubric versions.
- Run hundreds of scenarios over hours against a live service, surviving restarts and
  deployments.
- Separate a wrong answer from a run that produced no result.

### <a href="about:blank#_when_not_to_use_evalkit"></a> When NOT to use evalkit

- **Benchmarking a model against academic test sets**, use a model benchmark runner. MMLU
  and GSM8K measure a model and say nothing about a service.
- **Asserting deterministic component behaviour**, use `TestKitSupport` and ordinary unit
  tests. A decision with a right answer needs no evaluation.
- **Watching production quality continuously**, use an
  [evaluator agent](../../sdk/agents/llm_eval.html) called from a Consumer. evalkit runs
  campaigns against a dataset.

### <a href="about:blank#_the_system_under_test"></a> The system under test

evalkit reaches the service through `SystemUnderTest`. The service can be an Akka service,
an HTTP endpoint, or a process in another language.

[SystemUnderTest.java](https://github.com/tylerjewell/evalkit/blob/main/evalkit-core/src/main/java/io/akka/evalkit/domain/SystemUnderTest.java)
```java
public interface SystemUnderTest {

  Prepared prepare(Precursor precursor); // (1)

  Reply submit(String sessionId, String userText); // (2)

  Map<String, String> fixtures(); // (3)

  default Set<String> emittableNodes() { // (4)
    return Set.of();
  }

  default Accounting spend() { // (5)
    return new Accounting(Tokens.NONE, 1);
  }
}
```

| **1** | Put the service into the state a scenario assumes. A precursor that cannot land returns `Prepared.Failed` and becomes `NotReached`, which is a fact about the campaign. |
| **2** | Say the graded turn and return what the service said back. `Reply` carries the text, the specification node, the latency, the tool calls, the model calls and the failure that ended the run, and a target supplies whichever of those it can observe. |
| **3** | States this target can construct, each with a one-line description. A campaign naming a fixture the target lacks is refused before anything is spent. |
| **4** | Specification nodes this target can emit. An empty set means unknown, so the check is skipped and the campaign still runs. |
| **5** | Tokens the service spent answering. The default reports nothing measured, and the report labels the total a floor. |

## <a href="about:blank#_outcomes"></a> Outcomes a run can produce

`RunOutcome` is a sealed interface. Switches over it are exhaustive and carry no `default`,
so a new variant is a compile error at every site that must handle it.

| Outcome | What happened | Counted |
|---|---|---|
| `Scored` | A judge read the reply and gave it a score. | Yes |
| `Asserted` | The reply was compared against a stated answer. No model was involved. | Yes |
| `Measured` | A metric computed a number and compared it to a threshold. | Yes |
| `NotReached` | The setup failed, or the service sent nothing back. | No |
| `Inconclusive` | The scorer ran and reached no conclusion. | No |
| `Failed` | The scorer itself broke. | No |

`Inconclusive` was added after a content filter refused to score an identification-failure
transcript during calibration. Dropping that run would have raised the reported agreement
between the judge and the human reviewer above the figure the run earned.

`Failed` separates a defect in evalkit from a property of the transcript. A judge that
declines says so by throwing `InconclusiveScore`, and every other exception reaching the runner is
recorded as this kit failing.

## <a href="about:blank#_modules"></a> Modules

```
evalkit-core      scenarios, runner, scorers, metrics, reports      no dependencies
evalkit-akka      durable campaigns, agent judges                   Akka SDK
```

`evalkit-core` compiles against the JDK alone. A service written in another language,
reachable over a port, is evaluated by implementing one interface. Adding a dependency to
`evalkit-core` requires an argument that convenience does not satisfy.

`evalkit-akka` currently builds against an Akka SDK published by hand from an unmerged
branch, which the [Build section of the README](https://github.com/tylerjewell/evalkit#build)
describes.

## <a href="about:blank#_getting_started"></a> Getting started

```xml
<dependency>
  <groupId>io.akka.evalkit</groupId>
  <artifactId>evalkit-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

A campaign needs scenarios, a target, and a router that decides how each scenario is
settled.

```java
var dataset = List.of(
  new Scenario("refund-30d",
    Optional.of("REFUND-004"),                                  // (1)
    Precursor.Fixture.named("authenticated-claim-open"),
    "What if these shoes don't fit?",
    "a 30-day full refund at no extra cost"));

var plan = new CampaignPlan("refund-policy", dataset, Lanes.of(4), rubric); // (2)

if (plan.check(target) instanceof CampaignPlan.Check.Refused refused) { // (3)
  throw new IllegalStateException(String.join("; ", refused.reasons()));
}

var result = CampaignRunner.run(plan, target, router); // (4)
System.out.println(result.report().summary()); // (5)
```

| **1** | The specification node this scenario exercises. Naming one settles the run by comparison, so no model is called. A scenario naming no node and no metric is judged. |
| **2** | Lanes set how many scenarios run at once. The report states how many the run sustained. |
| **3** | Pre-flight. Missing fixtures and unemittable nodes are refused in the first second. |
| **4** | Every scenario produces a row. A scenario whose run throws produces `NotReached` carrying the reason. |
| **5** | One line for a build log. `RunSummary.results` renders the terminal-width report written for a reader who does not know the service. |

## <a href="about:blank#_see_also"></a> See also

- [Scoring](scoring.html)
- [Testing with the Akka TestKit](testing.html)
- [LLM evaluation in the Akka SDK](../../sdk/agents/llm_eval.html)
