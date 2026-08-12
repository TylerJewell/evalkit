# evalkit — working instructions

An evaluation harness for conversational services. Put the system in a known state,
say one thing to it, score what came back, and report what the run is **not** allowed
to claim.

Read `docs/design-history.md` before changing anything load-bearing, when the working
copy has it. It is kept out of the published repository. Most rules here exist because
something went wrong, and the incident is more persuasive than the rule.

---

## The one thing this project is for

An eval harness is trusted more than it deserves. Its output is a number that goes to
people who cannot check it, and every failure mode is silent: a campaign that ran half
its scenarios, an audit that examined nothing, a judge scoring pure functions, a score
that measures the harness rather than the product.

**So the work is not "produce a number". It is "produce a number that cannot lie."**
When those conflict, the number loses.

---

## Module boundary

```
evalkit-core   scoring, outcomes, reporting, evaluators   akka-javasdk
evalkit-akka   durable campaigns, model judge             Akka SDK + TestKit
```

**A type comes from the SDK when it carries evidence. A type stays in evalkit when it
holds an invariant the SDK does not enforce.** `InteractionRecord`, `ModelResponse`,
`ToolCall` and `Failure` are the SDK's. `Grade` pairs a 1-to-10 score with its `Band`
and rejects a pair that disagrees. `RunOutcome` carries `NotReached`, which no ledger
record represents, because an interaction that never happened was never recorded.

**Where the SDK has a word, the SDK's word wins.** `RunOutcome.Inconclusive` and
`RunOutcome.Failed` carry the names `EvaluationRecord.Outcome` gives those two facts.
The SDK spends `Verdict` on the outcome category, so the judge's 1-to-10
result is `Grade`. `Observation` is evalkit's bundle of a
transcript and an `InteractionRecord`, named so it cannot be misread as the SDK's
record. `Subject` is the SDK's name for what an evaluation ran over, so a `Finding`
names its `claim`.

**`explanation` and `reason` are not interchangeable.** An explanation says why the
subject passed or failed, and `Grade` and `Measured` carry it. A reason says why there
is no conclusion at all, and `Inconclusive`, `Failed` and `NotReached` carry that.
Nothing carries both.

**"failed" is never used bare.** The subject failing is `passed=false`. The machinery
failing is `Failed`. A sentence, a field or a method that says only "failed" leaves a
reader unable to tell a finding about the product from a defect in this kit.

Adding a dependency beyond `akka-javasdk` still requires an argument, and "it would be
convenient" is not one. A service in another language behind an HTTP port is still
evaluated by implementing `SystemUnderTest`, and the adapter implementing it is Java.

The aggregator POM parents nothing: `evalkit-core` has no parent, `evalkit-akka`
inherits `akka-javasdk-parent` because its TestKit needs the build that supplies.
Making the aggregator the parent broke the TestKit's config; don't re-tidy it.

---

## Rules that are load-bearing

**No single number.** The runs behind a campaign are not the same kind of thing. One
percentage has to pick a lie to tell about the rest. `CampaignReport` refuses to
produce one and should keep refusing.

**A deterministic result is a pass or a fail, never undecided.** Comparison has no
confidence to be borderline about. The report prints deterministic and judged in
separate columns, with a dash — not a zero — in the deterministic undecided cell,
because a figure there would be a bug in this kit rather than a finding about the
system.

**Absent evidence is never a verdict.** A judge that times out, refuses, or returns an
unreadable score gives `Inconclusive`. A precursor that does not land gives
`NotReached`. Neither is a failure of the system under test, and neither is folded
into a pass rate.

**Every audit needs a case it is known to catch.** A green test and an empty search
space look identical from the outside. If a check can pass by finding nothing, it must
have a test proving it finds something.

**A suppression list may only shrink.** Any known-gaps list needs a second test that
fails when an entry outlives its cause. Wiring something removes its entry in the same
change.

**Refuse before running, not after.** A campaign that fails at minute forty for a
reason knowable at minute zero wasted forty minutes. `CampaignPlan.check` validates
fixtures and emittable nodes up front. Add to it whenever a new class of
unsatisfiable campaign appears.

**Unaccounted model calls make a total a floor.** A target that cannot see its own
usage says so. Never present zero as a measurement.

---

## The report

`RunSummary` is the standard and its voice is deliberate: terminal-width, plain,
written for a compliance or risk reader who does not know the system. Not for a model.

- Say what will be tested before the run, and what was found after.
- Explain the judge's bands and its measured agreement, so a reader knows what a score
  is worth.
- Name the file the scenarios came from, in project-agnostic language.
- Close with what the kit cannot show: it can prove the system answered correctly from
  a stated starting point; it cannot prove a user reaches that point unaided.
- Never pad with caveats. State the limit once, plainly, and move on.

Do not add a number to the report that a reader should not quote. If a figure measures
the harness rather than the product, say so in prose instead.

---

## Working style

- Java records for data; sealed interfaces with exhaustive switches and no `default`
  for outcomes, so a new outcome is a compile error at every site that must handle it.
- Comments describe what is true now and why, never the work in progress. The test is
  whether the comment still reads correctly with the history deleted. Chronology
  belongs in commit messages.
- Match the surrounding style. Touch only what the task requires.
- Prefer the Edit tool over shell heredocs for anything containing regex or escapes —
  Java string escaping through Python heredocs has corrupted files repeatedly.
- Build: `mvn install`, which needs a locally published `feature/governance` SDK — see the
  README's Build section. Core alone: `mvn -f evalkit-core/pom.xml test`, which needs the
  same SDK and is the loop to work in.

## Before publishing

`evalkit-akka` pins `3.6.0-59-7321c44b-dev-SNAPSHOT`, an Akka SDK built by hand from an
unmerged branch. Nothing can be released while that pin stands, and continuous integration
cannot build the module either. Reverting to a released SDK costs the evaluation and ledger
APIs, so the pin holds until the branch ships.

Version is `0.1.0-SNAPSHOT` and the intended home is a registry, eventually Akka's.
Three things are known-wrong and are in the README's Status section:

1. Surefire pinned to 2.22.2 because that is what was cached offline — raise it once
   building against a real repository.
2. `groupId` is `io.akka.evalkit`, which presumes a namespace not yet granted.
3. Rubric v3 returns a sentence beside the score on v2's bands, and v4 asks for it under
   `EXPLANATION` where v3 asks under `REASON`. Neither one's agreement with v2 is measured
   until `JudgeCalibrationTest` runs with `-Dcalibration.compare=true`, and a label is
   still a prompt: whether a judge writes differently under the two words is unknown until
   that run. Token accounting understates any agent whose memory is off.
