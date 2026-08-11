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

The rules above were written after incidents. That record is kept outside this
repository, so ask before changing a rule that reads as arbitrary.

## Opening an issue

Use GitHub issues. A report of a wrong number is most useful with the scenario,
the outcome the run produced, and the outcome you expected. A report about a
metric is most useful with the judgements that produced the score, because
`aggregate` is a pure function and those judgements reproduce it exactly.

## Before you open a pull request

```shell
mvn -f evalkit-core/pom.xml test                         # needs the Akka repository
python tools/audit-prose.py docs/specs/*.html docs/site/evalkit/*.html.md README.md
python tools/audit-prose.py --check-waivers README.md
```

Build `evalkit-core` from its own POM. Maven reads every module POM in the
aggregator before it honours `-pl`, and `evalkit-akka` inherits an SDK parent
whose plugins pull more than the core tests need.

`evalkit-core` depends on `akka-javasdk`, so a pull request from a fork builds
neither module until the SDK ships.

### Building evalkit-akka

`evalkit-akka` builds against an unreleased Akka SDK. The evaluation and ledger
APIs it uses live on the `feature/governance` branch of `akka/akka-sdk`, and that
branch publishes no artifacts, so the SDK is built locally until it ships.

1. Install the Akka Specify plugin in your AI coding assistant, following
   [the setup guide](https://doc.akka.io/getting-started/set-up-dev-env.html).
2. Run `/akka:setup`, which configures the CLI, Java, Maven and your Akka
   download token, and writes the repository into `~/.m2/settings.xml`.
3. Add a second `repository` and a matching `pluginRepository` to
   `~/.m2/settings.xml`, each with `/snapshots` appended to the URL step 2 wrote.
   The runtime artifacts the SDK depends on are published there.
4. Clone `akka/akka-sdk` and check out the commit the version below names, add
   both URLs as resolvers in `project/plugins.sbt` and in `build.sbt`, then
   `publishM2` the `akka-javasdk`, `akka-javasdk-parent`, `akka-javasdk-testkit`,
   `akka-javasdk-validations`, `akka-javasdk-annotation-processor` and
   `akka-javasdk-enforcer` projects. The sbt project ids are those
   names. `build.sbt` declares them under different `lazy val` names. Pass the version, because the pin carries a
   suffix a clean checkout does not reproduce:

   ```
   sbt 'set every version := "3.6.0-59-7321c44b-dev-SNAPSHOT"' akka-javasdk/publishM2
   ```

5. Run `mvn install`.

Step 4 needs both resolver locations. sbt resolves a build's plugins before it
reads any global resolver file, so a resolver declared anywhere else leaves the
plugins unresolved.

**The checkout has to be a clone.** The SDK build reads its version from git
through jgit, which cannot open the object database through a linked worktree's
`.git` file and fails with `MissingObjectException`. On Windows the checkout also
needs `core.longpaths`, and a short root such as `C:\sdk-gov`, because some paths
in that repository exceed `MAX_PATH`.

**The `-dev` in the version is a dirty-tree marker.** `project/SdkVersion.scala`
appends it when the working tree carries uncommitted changes, so the pinned
version corresponds to no commit and no repository can serve it. A clean checkout
of `7321c44b` compiles and passes every evalkit test, so the changes that marker
records are not ones this project depends on.

Continuous integration skips this module while the SDK is built by hand.

The prose auditor reads documentation, specifications and the comments inside
code samples. Never pass it `conventions/prose.md`. That file quotes every construction it
bans, and it carries a marker that opts it out.

## What a change needs

**`evalkit-core` depends on `akka-javasdk` and on nothing else.** A type comes
from the SDK when it carries evidence, and a type stays in evalkit when it holds
an invariant the SDK does not enforce. Adding a third dependency requires an
argument, and convenience is not one. A service written in another language,
reachable over a port, is evaluated by implementing `SystemUnderTest`.

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

`conventions/prose-waivers.md` holds wording the auditor flags and the project
keeps. `--check-waivers` fails on an entry that covers nothing, so rewriting a
waived sentence removes its entry in the same change.

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
