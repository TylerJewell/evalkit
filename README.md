# evalkit

Puts a conversational service into a known state, sends it one message, scores
the reply, and prints a report that separates a wrong answer from a run that
produced no answer at all.

[![Build](https://github.com/tylerjewell/evalkit/actions/workflows/build.yml/badge.svg)](https://github.com/tylerjewell/evalkit/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

[Quickstart](#quickstart) · [Metrics](#metrics) · [Reports](#a-report) ·
[Build](#build) · [Contributing](CONTRIBUTING.md) · [Docs](docs/site/evalkit/)

## Quickstart

`evalkit-core` compiles against the JDK and nothing else.

```xml
<dependency>
  <groupId>io.akka.evalkit</groupId>
  <artifactId>evalkit-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

A scenario names the state to start from, the message to send, and the answer to
expect. A campaign runs a corpus of them against a `SystemUnderTest` and prints a
report.

```java
var corpus = List.of(
    new Scenario("refund-outside-window",
        Optional.of("REFUND-004"),                       // the requirement it exercises
        Precursor.replay("I want a refund", "order 4417"),
        "It has been 45 days. Can I still return it?",
        "Refuses, and states the 30-day window"));

var plan = new CampaignPlan("refund-policy", corpus, Lanes.of(8), router);

switch (plan.check(target)) {
    case CampaignPlan.Check.Refused r -> { r.reasons().forEach(System.err::println); return; }
    case CampaignPlan.Check.Ready ready -> System.out.println(RunSummary.scope(ready.plan()));
}

var result = CampaignRunner.run(plan, target, router);
System.out.println(RunSummary.of(result).render());
```

`plan.check(target)` runs before any call goes out. The check asks the service
which states it can build and which answers it can produce, compares those
against what the corpus needs, and refuses a campaign that cannot succeed. A
campaign that would fail at minute forty for a reason knowable at minute zero
stops in the first second.

## Metrics

A metric collects judgements and turns them into a number. Collecting may call a
model. `aggregate` is a pure function of the judgements, so it runs in a unit test
with no provider and no key.

```java
public interface Metric {
    MetricRef ref();
    double threshold();
    double aggregate(List<Judgement> judgements);   // pure
}
```

| Metric | Scores | Model calls | Origin |
|---|---|---|---|
| `ToolPermission` | Whether an agent called only the tools it was allowed to call | 0 | DeepEval |
| `TurnRelevancy` | The share of exchanges whose reply answered what was asked | 1 per exchange | DeepEval |
| `TurnFaithfulness` | The share of claims in a reply that the retrieved passages support | 1 per claim | DeepEval |
| `CitationFaithfulness` | Whether each citation points at a passage supporting the claim beside it | 1 per citation | DeepEval |
| `Dag` | The branch a judge chose through a decision graph | 1 per judgement node | DeepEval |

The five ported types take their expected values from
[DeepEval](https://github.com/confident-ai/deepeval)'s own tests at a pinned
commit, recorded in `NOTICE` and in `io.akka.evalkit.conformance.PortedMetrics`.
`ConformanceCoverageTest` fails the build when a ported metric has no fixture,
when a fixture names a metric that no longer exists, or when an entry records no
upstream commit.

`MetricRef` carries a version and `Scoring.compare` refuses to compare across
versions. Raising a threshold from 0.75 to 0.80 turns passing runs into failing
ones without touching the service, and a recorded score has to stay readable six
weeks later.

## How a scenario is settled

Routing happens before any call goes out, which is what makes a large corpus
affordable. On one recorded corpus, 510 of 514 scenarios named a specification
node and cost no model call at all.

| Family | Settles the result by | Model calls |
|---|---|---|
| Comparison | The reply reached the node the scenario named. | 0 |
| Computation | A metric scored the reply against a threshold. | 0 |
| Judgement | A model read the transcript against a versioned rubric. | 1 or more |

## Standalone or inside an Akka project

`evalkit-core` evaluates any service reachable over a port. Implement
`SystemUnderTest` and the rest of the kit works unchanged.

```java
public interface SystemUnderTest {
    Prepared prepare(Precursor precursor);       // get into position
    Reply submit(String sessionId, String userText);
    Map<String, String> fixtures();              // states this service can build
}
```

`evalkit-akka` runs inside an Akka project. A campaign becomes a durable
workflow that survives a restart, judges are Akka agents, and the whole service
starts under the Akka TestKit, so a campaign calls the real agents.

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

A campaign mixes checks settled by string comparison with numbers computed
against a threshold and replies scored by a model. Averaging them gives a
borderline model verdict the same weight as an exact match. `CampaignReport`
keeps the three in separate columns.

A comparison either matched the stated answer or missed it, and a number is
either inside its threshold or outside it. The undecided column shows a dash for
both, and a figure in either cell means this kit has a bug.

A judge that times out, hits a content filter, or returns something unreadable
reports nothing about the service. Such a run is recorded as `Unscoreable` and
stays out of the pass total.

A service may or may not report its own model usage. `Accounting` counts the
calls whose usage was invisible, and the report labels the total a floor.

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

Two measurements settled the rest of the report. A judge and an independent
reviewer agreed on 89 to 91 percent of clear replies and on 53 percent of the
replies scoring in the middle band, which is close enough to chance that the
middle band counts as undecided and never as a pass. Separately, one corpus
stated its expected answers without stating the inputs those answers depend on,
so an early version parsed the inputs from the scenario titles with a regular
expression: the same unchanged service scored 23, 17 and 19 out of 40 under
three versions of it, and a scenario now states its own setup as data.

`docs/design-history.md` records what went wrong before the rules were written.

## Roadmap

Tracked as GitHub issues. The open ones before a first release:

- publish to Maven Central, which needs a `groupId` this project owns
- raise the Surefire plugin from 2.22.2, pinned to what was cached offline
- return a reason alongside the score from rubric v2, which today returns a bare
  number
- count the tokens of an agent whose memory is off, which the current accounting
  understates

## Build

evalkit builds with Java 21 and Maven.

```shell
mvn -pl evalkit-core test    # the whole of evalkit-core, no setup needed
mvn install                  # adds evalkit-akka
```

`evalkit-core` declares no dependencies, so a clone and a JDK are enough.

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

## Documentation

- `docs/site/evalkit/` — reference documentation
- `docs/specs/` — design documents and the DeepEval comparison
- `docs/design-history.md` — what went wrong before the rules were written
- `CONTRIBUTING.md` — how a change gets in, and what every audit owes

## Contributing

Issues and pull requests are welcome. `CONTRIBUTING.md` covers the rules a
contributor would not expect, starting with the requirement that every audit
ships with a case it is known to catch.

## Licence

evalkit is licensed under Apache 2.0. See `LICENSE` and `NOTICE`.
