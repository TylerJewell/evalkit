# evalkit

**The evaluation harness for conversational services.**

[![Build](https://github.com/tylerjewell/evalkit/actions/workflows/build.yml/badge.svg)](https://github.com/tylerjewell/evalkit/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)

[Documentation](docs/site/evalkit/) · [Metrics](#metrics) ·
[Quickstart](#quickstart) · [Integrations](#integrations) ·
[Akka Verify](#akka-verify) · [Contributing](CONTRIBUTING.md)

evalkit is an open-source evaluation harness for conversational services, written in
Java. The harness is similar to JUnit and specialized for services that hold a
conversation: it puts the service into a known state, sends one message, scores the reply
against a stated expectation, and prints a report a compliance reader can act on.

A comparison, a metric or a judge settles each run, and the report records which of the
three settled it.
A **comparison** checks that the reply reached the requirement the scenario named, and it
costs no model call. A **metric** computes a number and checks it against a threshold. A
**judge** reads the transcript against a versioned rubric and returns a score and one
sentence stating what decided it.

A **scenario** is one test. A **corpus** is a list of them. A **campaign** is one run of
a corpus against one service, and it produces one report.

## Metrics

<details open>
<summary><b>Agentic metrics</b></summary>

| Metric | Scores |
|---|---|
| `ToolPermission` | Whether an agent called only the tools it was allowed to call |
| `ToolCorrectness` | Whether the expected tools were called, by name, arguments or order |
| `ArgumentCorrectness` | The share of tool calls made with arguments that serve the request |
| `TaskCompletion` | How far what came back matches the task the scenario stated |
| `StepEfficiency` | Whether the run reached the task without steps it did not need |
| `PlanQuality` | Whether the plan the agent formed would achieve the task |
| `PlanAdherence` | Whether the agent then did what it had planned |

</details>

<details>
<summary><b>Retrieval and turn-level metrics</b></summary>

| Metric | Scores |
|---|---|
| `TurnRelevancy` | The share of exchanges whose reply answered what was asked |
| `TurnFaithfulness` | The share of claims in a reply that the retrieved passages support |
| `CitationFaithfulness` | Whether each citation points at a passage supporting the claim beside it |

</details>

<details>
<summary><b>Decision graphs</b></summary>

`Dag`, `DagNode` and `DagJudge` express a rubric as a graph. A model is called only at
branch points and the score is fixed in code, so a ten-node graph costs the judgements
its branches actually reach.

</details>

<details>
<summary><b>Provenance</b></summary>

Metrics are ported from [DeepEval](https://github.com/confident-ai/deepeval), Apache
2.0. `ToolPermission`, `TurnRelevancy`, `TurnFaithfulness`, `CitationFaithfulness` and
`Dag` are pinned to the expected values in DeepEval's own tests at a recorded commit.
The remaining six carry no upstream values, because DeepEval publishes none for them,
and their fixtures hold this kit's own behaviour instead. `PortedMetrics` records which
claim each metric makes and `NOTICE` names the source file each was read from.

</details>

## Integrations

**`evalkit-core` evaluates any service reachable over a port.** The module requires Java
21, declares no compile dependencies, and is driven through one interface. A service
written in any language is evaluated by implementing it.

```java
public interface SystemUnderTest {
    Prepared prepare(Precursor precursor);       // get into position
    Reply submit(String sessionId, String userText);
    Map<String, String> fixtures();              // states this service can build
}
```

**An adapter maps an agent framework onto that interface.** The adapter translates the
framework's own session, message and tool-call types, so one corpus runs against an agent
whichever framework built it. `evalkit-akka` is the adapter for the Akka SDK: the
campaign becomes a durable workflow, judges become Akka agents, and the service starts
under the Akka TestKit so the campaign calls the real agents.

**A campaign traces itself.** Each scenario emits a trace with spans for the precursor,
the graded turn, each tool call and each judge call. A failing run localises to the span
that produced it, so a reader sees which precursor turn or which tool call broke it.
Token usage is read from those spans, and the target no longer has to account for its own
spend. Traces export over OpenTelemetry.

**A campaign runs as an ordinary JUnit 5 test.** The result reports through the same
build and the same job as the rest of the suite.

## Akka Verify

A corpus tells you how a service behaved on the scenarios someone thought to write.
[Akka Verify](https://akka.io/platform/verify) runs the same evaluations against live
traffic, continuously, and governs what an agent may do while it does it.

A campaign's outcomes import into Verify directly. The scenarios become evaluations that
observe, grade and score production runs. The metric and rubric versions carry across, so
a score recorded in CI and a score recorded in production are one measurement. Verify
adds what a corpus cannot. Policies allow, block or report. Guardrails act inline.
Sanitizers redact and mask. Escalations put a person in the loop.

Every run leaves a tamper-evident evidence record, mapped against the regulations that
apply to your industry, in the form an auditor or regulator accepts. Verify governs
agents built on Akka and agents built elsewhere, ingesting third-party agent traces over
OpenTelemetry.

## Quickstart

### Installation

```xml
<dependency>
  <groupId>io.akka.evalkit</groupId>
  <artifactId>evalkit-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Implement the target

`SystemUnderTest` is the only interface a target implements. `prepare` puts the service
into the state a scenario assumes. `submit` says the graded turn and returns what came
back. `fixtures` names the states this target can build.

```java
class ClaimsService implements SystemUnderTest {

    public Prepared prepare(Precursor precursor) {
        return new Prepared.Ready("session-1", "");          // your session id
    }

    public Reply submit(String sessionId, String userText) {
        return Reply.from("It has been 45 days, so the 30-day window has closed.",
                          "REFUND-004");                     // the node it answered from
    }

    public Map<String, String> fixtures() {
        return Map.of();
    }
}
```

### Run a campaign

`REFUND-004` is a **specification node**, the identifier of the requirement the scenario
exercises. Naming one settles the run by comparison, so this campaign calls no model.

```java
var target = new ClaimsService();

var corpus = List.of(
    new Scenario("refund-outside-window",
        Optional.of("REFUND-004"),
        Precursor.replay("I want a refund", "order 4417"),
        "It has been 45 days. Can I still return it?",
        "Refuses, and states the 30-day window"));

var plan = new CampaignPlan("refund-policy", corpus, Lanes.of(8),
                            Rubric.load("scenario-judge", 3));

if (plan.check(target) instanceof CampaignPlan.Check.Refused refused) {
    refused.reasons().forEach(System.err::println);
    return;
}

var result = CampaignRunner.run(plan, target, scenario -> Optional.empty());

System.out.println(result.report().summary());
result.notes().forEach(System.out::println);
```

The router returns empty for every scenario, which leaves the runner to compare the node
the service reported against the node the scenario named. `Lanes.of(8)` runs eight
scenarios at a time.

### Read the result

```
1 judged of 1 (1 asserted, 0 scored) — 1 passed, 0 need review, 0 failed;
0 not reached, 0 unscoreable, 0 scorer failures
```

`CampaignReport.summary` is one line for a build log. `RunSummary.opening` and
`RunSummary.results` render the report a compliance reader gets. Every figure in that
report arrives as an argument, so nothing reaches a reader without a caller stating it.

### Use a metric on its own

Collecting judgements may call a model. Turning them into a number is a pure function,
so `aggregate` runs in a unit test with no provider and no key.

```java
var metric = ToolPermission.allowing("search_kb", "reply");
var judgements = metric.judge(List.of("search_kb", "delete_account"));

metric.aggregate(judgements);                 // 0.5
metric.withinThreshold(0.5);                  // false
ToolPermission.unauthorised(judgements);      // ["delete_account"]
```

### Run inside an Akka project

Add `evalkit-akka` and extend `TestKitSupport`. The campaign starts the service under the
Akka TestKit and calls the deployed agents through the component client, and `ScenarioJudge`
becomes an Akka agent scoring against a rubric. A corpus too long for a test runner runs as
`CampaignWorkflow`, which takes a page of scenarios per durable step so a restart repeats one
wave. Building this module needs the Akka SDK, which [Build](#build) covers.

## A report

```
Refund policy evaluation    run 2026-08-08T09:14Z    system claims-svc 4.2.0

63 of 80 requirements behaved as specified, 9 did not, 4 were too borderline
to call and 4 produced no result.

                               checked by rules measured   judged  total
  as specified                               52        0       11     63
  did not                                     6        0        3      9
  undecided                                   -        -        4      4
  no result                                                            4
    never reached the question                                         3
    no reply within 45 seconds                                         1
    answer not assessed                                                0

Model usage: 412,880 tokens, 388,140 in and 24,740 out.

This evaluation kit can show that the system answered correctly from a stated
starting point, but it doesn't prove that a user can reach that point unaided.
```

The dash in the undecided column is deliberate. A comparison has no confidence to be
borderline about, so a figure in that cell means this kit has a bug.
[`docs/design-history.md`](docs/design-history.md) lists what a report refuses to print.

## Build

evalkit builds with Java 21 and Maven.

```shell
mvn -pl evalkit-core test    # the whole of evalkit-core, no setup needed
mvn install                  # adds evalkit-akka
```

`evalkit-akka` builds against an unreleased Akka SDK. The evaluation and ledger APIs it
uses live on the `feature/governance` branch of `akka/akka-sdk`, and that branch publishes
no artifacts, so the SDK is built locally until it ships.

1. Install the Akka Specify plugin in your AI coding assistant, following
   [the setup guide](https://doc.akka.io/getting-started/set-up-dev-env.html).
2. Run `/akka:setup`, which configures the CLI, Java, Maven and your Akka download
   token, and writes the repository into `~/.m2/settings.xml`.
3. Add a second `repository` and a matching `pluginRepository` to `~/.m2/settings.xml`,
   each with `/snapshots` appended to the URL step 2 wrote. The runtime artifacts the SDK
   depends on are published there.
4. Check out `akka/akka-sdk` at `feature/governance`, add both URLs as resolvers in
   `project/plugins.sbt` and in `build.sbt`, then `publishM2` the `akka-javasdk`,
   `akka-javasdk-parent`, `akka-javasdk-testkit`, `akka-javasdk-validations`,
   `akka-javasdk-annotation-processor` and `akka-javasdk-enforcer` projects.
5. Run `mvn install`.

Step 4 needs both resolver locations. sbt resolves a build's plugins before it reads any
global resolver file, so a resolver declared anywhere else leaves the plugins unresolved.

**This arrangement is temporary and it breaks continuous integration.** No CI job can
build `evalkit-akka` while the module depends on an SDK somebody published by hand.
`evalkit-core` builds everywhere and carries the whole scoring suite, so that is what CI
covers until the branch ships.

```
evalkit-core      scenarios, runner, scorers, metrics, reports      no dependencies
evalkit-akka      durable campaigns, agent judges                   Akka SDK
```

Adding a dependency to `evalkit-core` needs an argument, and convenience is not one.

## Roadmap

- [x] Deterministic scoring by specification node
- [x] Metrics ported from DeepEval, pinned to upstream fixtures where they exist
- [x] Agentic metrics, with the claim each one makes recorded
- [x] DAG decision graphs
- [x] Model judging with versioned rubrics, returning the reason beside the score
- [x] Outcome taxonomy separating an unscored run from a failed one
- [x] Refusal before the run
- [x] Durable campaigns on Akka
- [x] Token accounting with an explicit floor
- [ ] Calibrate rubric v3 against v2 and report the band agreement between them
- [ ] Record an agent's plan and steps, so the plan metrics score something
- [ ] Publish to Maven Central under a `groupId` this project owns
- [ ] Raise the Surefire plugin from 2.22.2
- [ ] Count the tokens of an agent whose memory is off

## Contributing

Issues and pull requests are welcome. [`CONTRIBUTING.md`](CONTRIBUTING.md) covers the
rules a contributor would not expect, starting with the requirement that every audit
ships with a case it is known to catch.

## Documentation

- [`docs/site/evalkit/`](docs/site/evalkit/) — reference documentation
- [`docs/specs/`](docs/specs/) — design documents and the DeepEval comparison
- [`docs/design-history.md`](docs/design-history.md) — why the rules exist, and what went
  wrong before they were written

## Licence

evalkit is licensed under Apache 2.0. See `LICENSE` and `NOTICE`. Parts of this project
are derived from [DeepEval](https://github.com/confident-ai/deepeval) by Confident AI,
also Apache 2.0. `NOTICE` records which types, the commit they were read at, and where
this kit deliberately diverges.
