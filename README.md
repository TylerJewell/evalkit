# evalkit

Runs recorded scenarios against a conversational service and prints a report of
the result.

[![Build](https://github.com/akka/evalkit/actions/workflows/build.yml/badge.svg)](https://github.com/akka/evalkit/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

```java
var plan = new CampaignPlan("refund-policy", corpus, Lanes.of(8), router);

switch (plan.check(target)) {
    case CampaignPlan.Check.Refused r -> { r.reasons().forEach(System.err::println); return; }
    case CampaignPlan.Check.Ready ready -> System.out.println(RunSummary.scope(ready.plan()));
}

var result = CampaignRunner.run(plan, target, router);
System.out.println(RunSummary.of(result).render());
```

## What a run can produce

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

## Why the outcomes are split this way

Say you run 80 scenarios and 64 come back with a low score. You want to know
whether the service is broken. The score on its own cannot tell you, because
three different things produce a low number and they look identical in a report.

The first is the one you were testing for: the service answered, and the answer
was wrong.

The second is a setup failure. Most scenarios do not test a first message. A
scenario might need a signed-in customer with a claim already open, and getting
there means either replaying earlier turns or seeding the state directly. If
that step fails, the graded question is never asked and the service never
answers. Score the empty reply and you get a low number that reads as a broken
service.

The third is a judge failure. A judge is a model reading a transcript, and
models decline. While calibrating this kit, a content filter refused to score a
transcript about a failed identity check. Turn that refusal into a zero and the
service takes the blame for the filter.

`RunOutcome` therefore has five cases and no single number. `Scored`,
`Asserted` and `Measured` mean the service answered and the answer was assessed.
`NotReached` and `Unscoreable` mean nothing was assessed. The report counts them
separately and leaves the last two out of the pass total.

The same problem turns up in two more places.

Judges disagree. On one corpus, a judge and a human reviewer agree 89 to 91
percent of the time on clear-cut replies, and 53 percent of the time on
borderline ones. At 53 percent a judge is close to a coin toss, so the middle
band counts as undecided instead of as a pass.

Small changes to the evaluation code move the score. One corpus stated its expected
answers but not the inputs those answers depend on, so the inputs were pulled
out of the scenario titles by pattern. Three attempts at that pattern scored
23, 17 and 19 out of 40. The service never changed. A number that moves that far when you edit a regex is a number about the regex, so a scenario now
states its own setup as data.

`docs/design-history.md` records the incident behind every design rule in the codebase.

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

## What a report will not show you

A report will not show you:

- one pass-rate percentage for the whole campaign
- an undecided result for a check settled by comparison or by a threshold
- a zero for a run the judge would not score
- a token total the service could not measure, printed as a measurement
- any result at all when the service cannot satisfy the scenarios

A reader would quote any of those five figures. The report prints counts by
category instead, so you can add up the ones that belong together.

A campaign mixes checks settled by string comparison with numbers computed
against a threshold and replies scored by a model. Averaging them gives a
borderline model verdict the same weight as an exact match. `CampaignReport`
keeps the three in separate columns.

A comparison either matched the stated answer or missed it, and a number is
either inside its threshold or outside it. The undecided column therefore shows
a dash for both, and a figure in either cell means this kit has a bug.

A judge that times out, hits a content filter, or returns something unreadable
tells you nothing about the service. Such a run is recorded as `Unscoreable`
and stays out of the pass total.

A service may or may not report its own model usage. `Accounting` counts the
calls whose usage was invisible, and the report labels the total a floor.

`CampaignPlan.check` asks the service which states it can build and which
answers it can produce, then compares that against what the scenarios need. A
mismatch stops the run in the first second.

## How a scenario is settled

Routing happens before any call goes out, which is what makes a large corpus
affordable. On one recorded corpus, 510 of 514 scenarios named a specification
node and cost no model call at all.

| Family | Settles the result by | Model calls |
|---|---|---|
| Comparison | The reply reached the node the scenario named. | 0 |
| Computation | A metric scored the reply against a threshold. | 0 |
| Judgement | A model read the transcript against a versioned rubric. | 1 or more |

A metric splits into two halves. Collecting judgements may need a model and
cannot be pinned, because a model samples. Turning judgements into a number is a pure
function, and that half runs in a unit test with no provider and no key.

```java
public interface Metric {
    MetricRef ref();
    double threshold();
    double aggregate(List<Judgement> judgements);   // pure
}
```

## Metrics ported from DeepEval

`ToolPermission`, `TurnRelevancy`, `TurnFaithfulness`, `CitationFaithfulness`
and the DAG decision graph are ported from
[DeepEval](https://github.com/confident-ai/deepeval) under Apache 2.0. Every
expected value comes from DeepEval's own tests at a pinned commit, recorded in
`NOTICE` and in `io.akka.evalkit.conformance.PortedMetrics`.

`ConformanceCoverageTest` fails the build when a ported metric has no fixture,
when a fixture names a metric that no longer exists, or when an entry records no
upstream commit.

## Modules

```
evalkit-core      scenarios, runner, scorers, metrics, reports      no dependencies
evalkit-akka      durable campaigns, agent judges                   Akka SDK
```

`evalkit-core` compiles against the JDK alone. A service in any language behind
a port is evaluated by implementing `SystemUnderTest`.

## Build

evalkit builds with Java 21 and Maven.

```shell
mvn install                  # all modules
mvn -pl evalkit-core test    # core alone, no runtime needed
```

```xml
<dependency>
  <groupId>io.akka.evalkit</groupId>
  <artifactId>evalkit-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Documentation

- `docs/site/evalkit/` — reference documentation
- `docs/specs/` — design documents and the DeepEval comparison
- `docs/design-history.md` — the incident behind every design rule
- `CONTRIBUTING.md` — how a change gets in, and what every audit owes

## Licence

evalkit is licensed under Apache 2.0. See `LICENSE` and `NOTICE`.
