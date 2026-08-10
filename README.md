# Akka Evalkit

**Evaluations for conversational AI.**

[![Build](https://github.com/tylerjewell/evalkit/actions/workflows/build.yml/badge.svg)](https://github.com/tylerjewell/evalkit/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)

[Documentation](docs/site/evalkit/) · [What it measures](#what-it-measures) ·
[Quickstart](#quickstart) · [Works with](#works-with) ·
[Akka Verify](#akka-verify) · [Contributing](CONTRIBUTING.md)

Akka Evalkit is a simple-to-use, open-source LLM evaluation framework, for evaluating large-language model systems. It is similar to Pytest and DeepEval but specialized for unit testing LLM apps and agentic systems authored in Java. Evalkit incorporates the latest research to run evals via metrics such as G-Eval, task completion, answer relevancy, and  hallucinations, which uses LLM-as-a-judge and other NLP models that run locally on your machine.

With Evalkit, you can easily evaluate:
* LLM apps end-to-end as black boxes
* Complete agent trajectories across every decision and action
* Individual agent steps such as LLM calls, tool use, retrieval, and sub-agent handoffs

Use these evaluations to determine the optimal models, prompts, and architecture to improve your AI quality, prevent prompt drifting, or even transition from OpenAI to Claude with confidence.

Evalkit runs campaigns using Akka's durable execution engine, in order to support long-lived, long-process evaluations that cover millions of scenarios.  With durable execution campaigns, you can restart the evaluation engine and continue where the campaign left off.

## Metrics and measurements

<details open>
<summary><b>Agentic behaviour</b></summary>

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
<summary><b>Retrieval and conversation quality</b></summary>

| Metric | Scores |
|---|---|
| `TurnRelevancy` | The share of exchanges whose reply answered what was asked |
| `TurnFaithfulness` | The share of claims in a reply that the retrieved passages support |
| `CitationFaithfulness` | Whether each citation points at a passage supporting the claim beside it |

</details>

<details>
<summary><b>Your own criteria</b></summary>

A rubric is a judge's prompt, versioned so a score stays interpretable months later.
Decision graphs express a rubric as a tree and call a model only where the branch is
genuinely in doubt.

</details>

## Reports

```
Refund policy evaluation    run 2026-08-08T09:14Z    system claims-svc 4.2.0

63 of 80 requirements behaved as specified, 9 did not, 4 were too borderline
to call and 4 produced no result.

                                           rules   measured judged  total
  behaved as specified                       52        0       11     63
  did not behave                              6        0        3      9
  undecided                                   -        -        4      4
  no result                                                            4
    never reached the question                                         3
    no reply within 45 seconds                                         1
    answer not assessed                                                0

Model usage: 412,880 tokens, 388,140 in and 24,740 out.
```

## Quickstart

```xml
<dependency>
  <groupId>io.akka.evalkit</groupId>
  <artifactId>evalkit-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

A case names the requirement it exercises. `REFUND-004` below is one, and naming it lets
evalkit settle the case without paying for a model.

```java
var corpus = List.of(
    new Scenario("refund-outside-window",
        Optional.of("REFUND-004"),
        Precursor.replay("I want a refund", "order 4417"),
        "It has been 45 days. Can I still return it?",
        "Refuses, and states the 30-day window"));
```

Run the corpus against your service and print the result.

```java
var plan = new CampaignPlan("refund-policy", corpus, Lanes.of(8),
                            Rubric.load("scenario-judge", 3));

var result = CampaignRunner.run(plan, target, router);

System.out.println(result.report().summary());
```

```
1 judged of 1 (1 asserted, 0 scored) — 1 passed, 0 need review, 0 failed;
0 not reached, 0 unscoreable, 0 scorer failures
```

Connecting a service takes one small adapter, which
[the documentation](docs/site/evalkit/index.html.md) covers.

## Where the eval code goes

A campaign costs provider spend and runs for minutes. A source root at `src/eval/java`
compiles under a Maven profile and stays off the build that runs on every commit.

```
<service>/
├── pom.xml               build-helper adds src/eval under the eval profile
├── specs/
│   └── refund-policy/
│       └── spec.md       acceptance criteria, the source of truth for spec nodes
├── src/
│   ├── main/java/…       the service
│   ├── test/java/…       unit and integration tests, run on every commit
│   └── eval/
│       ├── java/com/acme/evalkit/
│       │   ├── dataset/    ScenarioSource implementations, corpus loaders
│       │   ├── runner/     SystemUnderTest adapter, fixtures, lane configuration
│       │   ├── scorer/
│       │   │   ├── deterministic/  node match, exact match, schema, tool-call assertions
│       │   │   ├── heuristic/      similarity, JSON validity, latency and token budgets
│       │   │   └── agentic/        judge agents, panels, calibration
│       │   └── reports/    renderers and baseline comparison
│       └── resources/
│           ├── datasets/*.jsonl
│           ├── rubrics/*.txt      versioned, never edited in place
│           └── baselines/*.json   prior runs, for band movement
└── target/evalkit/       rendered reports and transcripts, gitignored
```

`reports/` holds renderers. A rendered report is build output and lands in `target/`. A
prior run kept so a new run can be compared against it is an input, and it lands in
`resources/baselines/` under version control.

```xml
<profile>
  <id>eval</id>
  <build><plugins>
    <plugin>
      <groupId>org.codehaus.mojo</groupId>
      <artifactId>build-helper-maven-plugin</artifactId>
      <executions><execution>
        <phase>generate-test-sources</phase>
        <goals><goal>add-test-source</goal></goals>
        <configuration><sources><source>src/eval/java</source></sources></configuration>
      </execution></executions>
    </plugin>
  </plugins></build>
</profile>
```

Campaigns run with `mvn -Peval verify`. The root is added as a test source, so the Akka
TestKit and JUnit stay on the classpath without a second dependency block.

An eval source root declares no endpoints and registers no components. The
`domain`/`application`/`api` convention the evalkit library follows would produce four
near-empty packages there, so the eval tree is organised by function.

## Recording a corpus once

A campaign spends provider money on the traffic it causes. `FileLedger` writes what each run
recorded into `datasets/` as markdown, and a later campaign scores those files under a new
rubric without reaching the service again.

```java
var corpus = FileLedger.open(Path.of("src/eval/resources/datasets"));
result.completed().forEach(completed ->
    completed.recording().ifPresent(recording -> corpus.save(recording.interaction())));
```

Each file holds one interaction, with the conversation in prose sections and the figures in
list items.

```markdown
# Interaction

- id: refund-outside-window-01
- session: session-42
- latency: PT1.4S

## System

You are a refund assistant.

## User

It has been 45 days. Can I still return it?

## Model call

### Thinking

The order is 45 days old, which is outside the window.

### Tool search_kb

```arguments
{"query":"return window"}
```

```response
30-day return window
```

### Reply

Our return window is 30 days, so this order sits outside it.

### Tokens

- input: 1204
- output: 38
```

The `id` field is what identifies an interaction. A file renamed while a corpus is tidied
still holds the interaction every recorded evaluation names. Two files claiming one id are
refused when the corpus is opened.

`RecordedInteractions` runs a campaign over that corpus, and reaches no service.

```java
var recorded = new RecordedInteractions(corpus, corpus.fixtures());
var result = CampaignRunner.run(plan, recorded, router);
```

## Works with

**Akka services.** Campaigns run against real agents under the Akka TestKit, and a corpus
too long for a test runner runs as a durable workflow that survives a deploy.

**Services built on anything else.** A service in another language, reachable over a port,
is evaluated through one small adapter.

**An existing build.** A campaign runs as an ordinary JUnit 5 test, so its result reports
through a job that already exists.

**Existing traces.** Campaigns emit OpenTelemetry spans, so a failing case points at the
step that produced it.

## Akka Verify

A corpus tells you how a service behaved on the cases someone thought to write.
[Akka Verify](https://akka.io/platform/verify) runs the same evaluations against live
traffic, continuously, and governs what an agent may do while it does it.

A campaign's results import into Verify directly, and the metric and rubric versions carry
across, so a score recorded in CI and a score recorded in production are one measurement.
Verify adds what a corpus cannot. Policies allow, block or report. Guardrails act inline.
Sanitizers redact and mask. Escalations put a person in the loop.

Every run leaves a tamper-evident evidence record, mapped against the regulations that
apply to your industry, in the form an auditor or regulator accepts. Verify governs agents
built on Akka and agents built elsewhere.

## Project status

Version 0.1.0-SNAPSHOT, and nothing is published to Maven Central yet. Both modules need
an Akka SDK built by hand from an unreleased branch, which
[CONTRIBUTING.md](CONTRIBUTING.md) covers. `evalkit-core` reads
`akka.javasdk.ledger.InteractionRecord` and extends `akka.javasdk.evaluation.Evaluator`,
so Java 21 alone no longer builds it.

## Roadmap

- [x] Scoring by named requirement, by metric, and by model judgement
- [x] Agentic, retrieval and conversation metrics
- [x] Decision graphs
- [x] Versioned rubrics that return the reason beside the score
- [x] Results that separate a failure from a run that produced nothing
- [x] Refusal before a campaign spends anything
- [x] Durable campaigns on Akka
- [ ] Measure how far the reasoned rubric moves a score against the plain one
- [ ] Record an agent's plan and steps, so the plan metrics score every run
- [ ] Publish to Maven Central

## Documentation

- [Reference documentation](docs/site/evalkit/)
- [Design documents and the DeepEval comparison](docs/specs/)
- [Design history](docs/design-history.md), which records what went wrong before the rules were written

## Contributing

Issues and pull requests are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) covers the rules
a contributor would not expect, starting with the requirement that every audit ships with
a case it is known to catch.

## Licence

evalkit is licensed under Apache 2.0. See `LICENSE` and `NOTICE`. Parts of this project
are derived from [DeepEval](https://github.com/confident-ai/deepeval) by Confident AI,
also Apache 2.0.
