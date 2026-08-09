# evalkit

**Evaluation for conversational AI, with a report a reviewer can act on.**

[![Build](https://github.com/tylerjewell/evalkit/actions/workflows/build.yml/badge.svg)](https://github.com/tylerjewell/evalkit/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)

[Documentation](docs/site/evalkit/) · [What it measures](#what-it-measures) ·
[Quickstart](#quickstart) · [Works with](#works-with) ·
[Akka Verify](#akka-verify) · [Contributing](CONTRIBUTING.md)

evalkit is an open-source evaluation library for teams shipping conversational AI. A team
records the cases the service has to handle. evalkit runs those cases against the service
and reports how it went, together with what the numbers are worth.

The audience is whoever has to stand behind the result: the engineer before a deploy, the
risk reviewer before a launch, the auditor afterwards.

## Why teams use it

**The report reaches a non-engineer.** Plain text states what was tested, what was found,
and what the run cannot show, for a reader who does not know the service.

**The numbers do not overclaim.** A campaign mixes exact checks, computed scores and model
judgements, and evalkit reports the three separately with no blended percentage.

**A run that produced nothing is never counted as a failure.** A setup that did not
complete and a judge that would not answer are each reported as their own result.

**A large corpus stays affordable.** A case that names the requirement it exercises is
settled without calling a model, so hundreds of cases run on every change.

**Long runs survive a restart.** A campaign against a live service can run for hours and
resume after a deploy.

**A service in any language can be evaluated.** The service can run on Akka, behind an
HTTP endpoint, or as a process in another language.

## What it measures

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

<details>
<summary><b>Where the metrics come from</b></summary>

Metrics are ported from [DeepEval](https://github.com/confident-ai/deepeval), Apache 2.0.
Five metrics reproduce the expected values in DeepEval's own tests. The remaining metrics
carry no upstream values, because DeepEval publishes none for them, and `NOTICE` records
which claim each metric makes.

</details>

## What you get back

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

The report will not print a single pass-rate percentage, a zero for a run nobody scored,
or a token total the service could not measure. What it refuses to print, and why, is in
[the design history](docs/design-history.md).

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

Version 0.1.0-SNAPSHOT, and nothing is published to Maven Central yet. `evalkit-core`
builds and tests anywhere with Java 21. `evalkit-akka` currently needs an Akka SDK built
by hand from an unreleased branch, which [CONTRIBUTING.md](CONTRIBUTING.md) covers.

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
