# Contributing to evalkit

Thank you for taking the time. This file covers what a change needs before it
can be merged, starting with the rules a contributor would not expect.

## Rules a contributor would not expect

**Every audit needs a case it is known to catch.** A green test and an empty
search space look identical from the outside. A reachability check in this
repository anchored its pattern on indentation that had already been stripped,
matched nothing, iterated an empty set, and passed for nine development stages.
Any check that can pass by finding nothing ships with a test that proves it
finds something.

**A suppression list may only shrink.** Any list of known exceptions carries a
second test that fails when an entry outlives its cause. Wiring something up
removes its entry in the same change.

`docs/design-history.md` records what went wrong before the rules in this file
were written.
Read it before changing anything the rules cover. The incident is more
persuasive than the rule.

## Opening an issue

Use GitHub issues. A report of a wrong number is most useful with the scenario,
the outcome the run produced, and the outcome you expected. A report about a
metric is most useful with the judgements that produced the score, because
`aggregate` is a pure function and those judgements reproduce it exactly.

## Before you open a pull request

```shell
mvn -pl evalkit-core test                                # no credentials needed
mvn install                                              # adds evalkit-akka
python tools/audit-prose.py docs/specs/*.html docs/site/evalkit/*.html.md
```

`evalkit-akka` depends on the Akka SDK, which is not on Maven Central. Install
the Akka Specify plugin in your AI coding assistant, following
[the setup guide](https://doc.akka.io/getting-started/set-up-dev-env.html), then
run `/akka:setup`. That configures the CLI, Java, Maven and your Akka download
token, and writes the repository into `~/.m2/settings.xml`.

A change confined to `evalkit-core` needs none of that, and continuous
integration covers `evalkit-core` on every pull request.

The prose auditor reads documentation, specifications and the comments inside
code samples. Never pass it `conventions/prose.md`. That file quotes every construction it
bans, and it carries a marker that opts it out.

## What a change needs

**`evalkit-core` declares no dependencies.** A service written in another
language, reachable over a port, is evaluated by implementing one interface.
Adding a dependency there requires an argument, and convenience is not one.
After touching that module, run `mvn -pl evalkit-core test` and confirm it
compiles with no Akka on the classpath.

**A new outcome variant is a compile error everywhere it matters.** `RunOutcome`
is sealed and every switch over it is exhaustive with no `default`. Adding a
variant breaks the build at each site that has to decide what the variant means,
which is the design working. `OutcomeCoverageTest` also fails until a campaign
produces the new variant and the report counts it.

**A ported metric arrives with its upstream values.** Add an entry to
`io.akka.evalkit.conformance.PortedMetrics` naming the upstream class, the file
and the commit the values were read at, and a conformance test asserting them.
`ConformanceCoverageTest` fails without both. Record the derivation in `NOTICE`.

**A metric splits collection from arithmetic.** `aggregate` is a pure function
of the judgements, so it runs under test with no provider and no key. Collecting
judgements may call a model. A metric that mixes the two has no testable half.

**A threshold change is a version change.** `MetricRef` and `Rubric` both carry
a version, and `Scoring.compare` throws across versions. Raising a threshold
from 0.75 to 0.80 turns passing runs into failing ones with no change to the
service, and a recorded score has to stay interpretable six weeks later.

## Prose

`conventions/prose.md` holds the rules for documentation, specifications,
Javadoc and commit messages, and `tools/audit-prose.py` enforces the part a
regex can reach. The auditor cannot hear diction, so read every new sentence aloud.

A new rule in the auditor arrives with a line in `tools/prose-fixtures.md` that it
catches, under `## Caught`. A rule bank is a set of checks that pass by finding
nothing, so a pattern that stops matching looks exactly like prose that improved.
`python tools/audit-prose.py --self-test` asserts every fixture still fires, and the
`## Not caught` section holds wording that has to stay clean, which fails a rule
widened past its shape.

Documentation states what a thing is and how it works. Rationale belongs in a
limited part of `README.md` and nowhere else.

## Commit messages

A commit message carries the chronology. A comment describes what is true now.
An explanation of what used to be wrong belongs in the message, which is
attached to the change and does not decay with the line.

## Licence

Contributions are accepted under the Apache License 2.0. By opening a pull
request you agree that your contribution is licensed under it.
