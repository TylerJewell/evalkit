# evalkit

**The evaluation harness for conversational services.**

[![Build](https://github.com/tylerjewell/evalkit/actions/workflows/build.yml/badge.svg)](https://github.com/tylerjewell/evalkit/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)

[Quickstart](#quickstart) · [Metrics and features](#metrics-and-features) ·
[Integrations](#integrations) · [Reports](#a-report) ·
[Contributing](CONTRIBUTING.md) · [Documentation](docs/site/evalkit/)

evalkit is an open-source evaluation harness for conversational services, written
in Java. The harness is similar to JUnit and specialized for services that hold a
conversation: it puts the service into a known state, sends one message, scores
the reply against a stated expectation, and prints a report a compliance reader
can act on. A reply is scored by comparison against a named requirement, by a
metric that computes a number, or by a model reading the transcript against a
versioned rubric, and the harness records which of the three settled each run.

The words below are used throughout. A **scenario** describes one test: an id, the
state the service has to be in before the test starts, the message to send, and
the answer expected back. A **corpus** is a list of scenarios. A
**campaign** is one run of a corpus against one service, and it produces one
report. A **judge** is a model that reads a transcript against a rubric and
returns a score from 1 to 10.

## Metrics and features

**Metrics ported from DeepEval**, each pinned to the expected values in DeepEval's
own tests at a recorded commit:

| Metric | Scores | Model calls |
|---|---|---|
| `ToolPermission` | Whether an agent called only the tools it was allowed to call | 0 |
| `TurnRelevancy` | The share of exchanges whose reply answered what was asked | 1 per exchange |
| `TurnFaithfulness` | The share of claims in a reply that the retrieved passages support | 1 per claim |
| `CitationFaithfulness` | Whether each citation points at a passage supporting the claim beside it | 1 per citation |
| `Dag` | The branch a judge chose through a decision graph | 1 per judgement node |

**A scenario naming a specification node costs no model call.** The scorer
checks which node the reply reached. A scenario naming a metric with
deterministic judgements is settled by arithmetic. On one recorded corpus, 510 of
514 scenarios were settled this way, which is what makes a large corpus
affordable.

**A judge reads the transcript against a versioned rubric.** A rubric is a
judge's prompt, held as data under `resources/rubrics/` and loaded by id and
version. Every `Verdict` carries the id and version that produced it, and
`Scoring.compare` refuses to compare scores across versions.

**`RunOutcome` separates a failure from an absence.** The sealed interface has
five variants. `Scored`, `Asserted` and `Measured` mean the service answered and
the answer was assessed. `NotReached` means the setup never completed, and
`Unscoreable` means the judge produced nothing readable. The last pair stays out
of the pass total.

**A campaign is refused before it runs.** `CampaignPlan.check` asks the service
which states it can build and which answers it can produce, compares those
against what the corpus needs, and refuses a campaign that cannot succeed.

**A scenario reaches its starting state by replay or by seeding.**
`Precursor.replay` walks the recorded turns through the same interface a user
would use, which is slow and proves the state is reachable. `Precursor.fixture`
asks the service to write the state directly, which costs no model calls.

**`RunSummary` writes for a reader who cannot check the work.** The report is
terminal-width plain text, and it states what will be tested before the run, what
was found after, the judge's bands and its measured agreement with a human
reviewer, and what the run cannot show. `Accounting` counts the model calls whose
token usage the service could not report, and labels the total a floor.

**A campaign bounds its concurrency and survives a restart.** `Lanes` sets how
many scenarios run at once. In an Akka project a campaign is a workflow that
writes a cursor after each wave, so a restart repeats one wave instead of the
whole campaign.

**A conformance suite pins each ported metric to its upstream values.**
`io.akka.evalkit.conformance` holds those fixtures, and
`ConformanceCoverageTest` fails the build when a ported metric has no fixture,
when a fixture names a metric that no longer exists, or when an entry records no
upstream commit.

## What evalkit adds

DeepEval established the metric shapes this project ports. The behaviour below
has no counterpart there.

**A run that produced no result is never a failure.** A judge that times out or
refuses gives `Unscoreable`, and a setup that does not complete gives
`NotReached`. The report keeps both out of the pass rate, because neither result
says anything about the service.

**A report prints no single pass-rate number.** A campaign mixes exact
comparisons, threshold computations and model verdicts. Averaging them gives a
borderline model verdict the same weight as an exact match, so `CampaignReport`
keeps the three in separate columns and prints no combined figure.

**A deterministic result is a pass or a fail, never undecided.** A comparison
either matched or missed, and a number is either inside its threshold or outside
it. The undecided column shows a dash for both, and a figure in either cell means
this kit has a bug.

**Campaigns survive a restart.** In the Akka module a campaign is a durable
workflow, so an hours-long run resumes after a deploy.

## Integrations

**`evalkit-core` evaluates any service reachable over a port.** The module
requires Java 21, declares no compile dependencies, and uses JUnit 5 and AssertJ
for its own tests. A service written in any language is evaluated by implementing
one interface.

```java
public interface SystemUnderTest {
    Prepared prepare(Precursor precursor);       // get into position
    Reply submit(String sessionId, String userText);
    Map<String, String> fixtures();              // states this service can build
}
```

**`evalkit-akka` runs a campaign inside an Akka project.** The campaign becomes
a durable workflow, judges become Akka agents, and the whole service starts under
the Akka TestKit, so the campaign calls the real agents.

**A campaign runs as an ordinary JUnit 5 test.** The result reports through the
same build and the same CI job as the rest of the suite.

## Quickstart

### Installation

```xml
<dependency>
  <groupId>io.akka.evalkit</groupId>
  <artifactId>evalkit-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### Write your first scenario

A **specification node** is the identifier of the requirement a scenario
exercises, and `REFUND-004` below is one.

```java
var corpus = List.of(
    new Scenario("refund-outside-window",
        Optional.of("REFUND-004"),                       // the requirement it exercises
        Precursor.replay("I want a refund", "order 4417"),
        "It has been 45 days. Can I still return it?",
        "Refuses, and states the 30-day window"));
```

### Run the campaign

`Lanes.of(8)` runs eight scenarios at a time. `target` is the `SystemUnderTest`.
The router is a `ScorerRouter`, which decides for each scenario whether the result
is settled by comparison, by a metric or by a model.

```java
var plan = new CampaignPlan("refund-policy", corpus, Lanes.of(8), router);

switch (plan.check(target)) {
    case CampaignPlan.Check.Refused r -> { r.reasons().forEach(System.err::println); return; }
    case CampaignPlan.Check.Ready ready -> System.out.println(RunSummary.scope(ready.plan()));
}

var result = CampaignRunner.run(plan, target, router);
System.out.println(RunSummary.of(result).render());
```

`plan.check(target)` runs before any call goes out, so a campaign that would fail
at minute forty for a reason knowable at minute zero stops in the first second.

### Use a metric on its own

A **judgement** is one yes-or-no observation about a reply, such as whether a
single tool call was authorised. A **metric** turns a list of judgements into a
number and compares that number against a threshold. Collecting the judgements
may call a model. Turning them into a number is a pure function, so `aggregate`
runs in a unit test with no provider and no key.

```java
var metric = ToolPermission.allowing("search_kb", "reply");
var judgements = metric.judge(List.of("search_kb", "delete_account"));

metric.aggregate(judgements);                 // 0.5
metric.withinThreshold(0.5);                  // false
ToolPermission.unauthorised(judgements);      // ["delete_account"]
```

### Run inside an Akka project

Add `evalkit-akka`, extend `EvalKitSupport`, and the campaign starts the service
under the Akka TestKit and calls the deployed agents through the component client.
Building this module needs the Akka SDK, which the [Build](#build) section covers.

## How a scenario is settled

Every scenario is routed to one of the three families below before any call goes
out.

| Family | Settles the result by | Model calls |
|---|---|---|
| Comparison | The reply reached the node the scenario named. | 0 |
| Computation | A metric scored the reply against a threshold. | 0 |
| Judgement | A model read the transcript against a versioned rubric. | 1 or more |

## What a run produces

Every scenario a campaign ran produces a row, and the row carries one of these.

| Outcome | What happened | Counted |
|---|---|---|
| `Scored` | A judge read the reply and gave it a score. | Yes |
| `Asserted` | The reply was compared against a stated answer. No model was involved. | Yes |
| `Measured` | A metric computed a number and compared it to a threshold. | Yes |
| `NotReached` | The setup failed, or the service sent nothing back. | No |
| `Unscoreable` | The judge timed out, refused, or returned something unreadable. | No |

Counted means the run is included when the report works out how many scenarios
passed. A run that is not counted gets its own line with its own reason.

`RunOutcome` is a sealed interface. Switches over it are exhaustive and carry no
`default`, so a new outcome is a compile error at every site handling one.

## A report

```
Refund policy evaluation    run 2026-08-08T09:14Z    system claims-svc 4.2.0

This run tests 80 requirements: refund window, delivery status, identity
checks and payment routing.

62 of the 80 are checked against fixed rules and cannot vary from one run to
the next. The other 18 produce free text for a customer and are judged by a
second model.

The second model reads the exchange and scores how closely the reply matches
the expected answer:

  8-10   matches faithfully          counted as specified
  4-7    matches in part             counted undecided
  1-3    does not match              counted as did not

It agrees with an independent reviewer 89-91% of the time on clear-cut replies
and 53% on borderline ones. The middle band is therefore counted undecided,
never as a pass.

--------------------------------------------------------------------------

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
  the system under test        351,200 in       21,900 out
  the judge                     36,940 in        2,840 out

This evaluation kit can show that the system answered correctly from a stated
starting point, but it doesn't prove that a user can reach that point unaided.
```

## What a report will not show

A report never prints:

- one pass-rate percentage for the whole campaign
- an undecided result for a check settled by comparison or by a threshold
- a zero for a run the judge would not score
- a token total the service could not measure, printed as a measurement
- any result at all when the service cannot satisfy the scenarios

A reader would quote any of those figures. The report prints counts by category
instead, and the categories that belong together add up.

## Why the outcomes are split this way

A low score by itself does not distinguish a wrong answer from a run that never
asked the question.

**The service answered and the answer was wrong.** `Scored`, `Asserted` and
`Measured` record a service that answered, whichever way the answer came out.

**The setup never completed.** A scenario needing a signed-in customer with an
open claim replays earlier turns or seeds the state directly, and a failure in
that step means the graded question was never asked. `NotReached` records it.

**The judge produced nothing to read.** During calibration a content filter
refused to score a transcript about a failed identity check. `Unscoreable`
records it.

Scoring a `NotReached` or an `Unscoreable` run as zero reports a working service
as a broken one, so the report counts both on their own lines.

A **band** is one of the three ranges a judge's score falls into: 8 to 10 matched,
4 to 7 matched in part, 1 to 3 did not match. A judge and an independent reviewer
agreed on 89 to 91 percent of clear replies and on 53 percent of the replies
scoring in the middle band, which is close enough to chance that the middle band
counts as undecided and never as a pass.

One corpus stated its expected answers without stating the inputs those answers
depend on, so an early version parsed the inputs from the scenario titles with a
regular expression. The same unchanged service scored 23, 17 and 19 out of 40
under three versions of that expression, and a scenario now states its own setup
as data.

`docs/design-history.md` records what went wrong before the rules were written.

## Build

evalkit builds with Java 21 and Maven.

```shell
mvn -pl evalkit-core test    # the whole of evalkit-core, no setup needed
mvn install                  # adds evalkit-akka
```

`evalkit-akka` depends on the Akka SDK, which is not published to Maven Central.
The Akka Specify plugin puts it on your machine:

1. Install the Akka Specify plugin in your AI coding assistant, following
   [the setup guide](https://doc.akka.io/getting-started/set-up-dev-env.html).
2. Run `/akka:setup`, which configures the CLI, Java, Maven and your Akka
   download token, and writes the repository into `~/.m2/settings.xml`.
3. Run `mvn install`.

Continuous integration builds `evalkit-akka` where that repository URL is
configured as the `AKKA_MAVEN_REPO_URL` secret, and covers `evalkit-core`
everywhere.

## Modules

```
evalkit-core      scenarios, runner, scorers, metrics, reports      no dependencies
evalkit-akka      durable campaigns, agent judges                   Akka SDK
```

A service written in any language behind a port is evaluated by implementing
`SystemUnderTest`. Adding a dependency to `evalkit-core` needs an argument, and
convenience is not one.

## Contributing

Issues and pull requests are welcome. `CONTRIBUTING.md` covers the rules a
contributor would not expect, starting with the requirement that every audit ships
with a case it is known to catch.

## Roadmap

- [x] Deterministic scoring by specification node
- [x] Metrics ported from DeepEval, pinned to upstream fixtures
- [x] DAG decision graphs
- [x] Model judging with versioned rubrics
- [x] Outcome taxonomy separating an unscored run from a failed one
- [x] Refusal before the run
- [x] Durable campaigns on Akka
- [x] Token accounting with an explicit floor
- [ ] Publish to Maven Central under a `groupId` this project owns
- [ ] Raise the Surefire plugin from 2.22.2
- [ ] Return a reason alongside the score from rubric v2
- [ ] Count the tokens of an agent whose memory is off

## Documentation

- `docs/site/evalkit/` — reference documentation
- `docs/specs/` — design documents and the DeepEval comparison
- `docs/design-history.md` — what went wrong before the rules were written
- `CONTRIBUTING.md` — how a change gets in, and what every audit owes

## Licence

evalkit is licensed under Apache 2.0. See `LICENSE` and `NOTICE`.

Parts of this project are derived from
[DeepEval](https://github.com/confident-ai/deepeval) by Confident AI, also
Apache 2.0. `NOTICE` records which types and the commit they were read at.
