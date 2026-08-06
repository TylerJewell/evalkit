# Design history

Why evalkit is shaped the way it is. Nearly every rule in `CLAUDE.md` exists because
something went wrong first, and the incident is more persuasive than the rule.

It was built against one service — a post-travel claims chatbot with a corpus of
several hundred recorded scenarios — as a replacement for a third-party evaluation
product. That origin matters: the failures below are what a real corpus and a real
service did to it, not what was imagined in advance.

---

## The harness lied about how much it ran

**53 outcomes for 80 scenarios, reported as complete.**

`invokeAll` parks a thrown exception inside a `Future` nobody reads. A scenario whose
run threw disappeared silently, and the campaign reported a smaller total than it was
asked to run — with no error anywhere.

Two fixes, both still in place: a catch-all so every scenario produces a row whatever
happens, and a hard check that the outcome count equals the scenario count before a
report is built.

**Then it mispaired what it did run.** Outcomes and precursors were kept in two
parallel lists, appended by concurrent workers. Nothing guaranteed `outcome[i]` and
`precursor[i]` described the same scenario, and the "walked" count was derived from
that pairing. Fixed with one `Completed` record holding both.

**The same bug then reappeared one level up.** `Result` handed back a bare
`List<RunOutcome>` in completion order, and callers zipped it by index against
`plan.scenarios()` in submission order. Under six lanes those differ. The per-scenario
CSV attributed results to the wrong requirement — visibly: a row whose `spec_node` was
`GenUC-16a.3` carried the detail "expected GenUC-17a".

**The lesson that generalises:** fixing a pairing hazard inside a class does nothing if
the type you hand back re-creates it. `Completed` is public and carries its `Scenario`
so there is nothing left to zip.

---

## The harness measured its own impatience

**26 of 80 runs recorded as "never reached" — the harness was just too eager.**

At six concurrent conversations against a live model, a 30-second reply timeout expired
before answers arrived. Those were reported as the system failing to respond.

The ceiling is now generous and settable. A slow reply is a latency finding, not a
missing one, and the report names the timeout in the row so a reader can tell which
they are looking at.

**A related one cost 24 of 40 runs**: the target waited for a bot turn that a
FAQ-style answer never wrote, because the answer only materialised when someone drained
the stream. Draining it took the campaign from 743 seconds to 18.

---

## An audit that examined nothing, and passed

A reachability check anchored its regex on four spaces of indentation, against source
that had every line trimmed before the pattern saw it. The pattern matched nothing. The
audit iterated an empty set and passed — for nine development stages — while its
suppression list was maintained by hand against a check that was examining nothing.

Fixing the anchor surfaced eleven suppressions describing work already done, and one
operation with no caller at all, which turned out to mean a delivered bag was being
assessed as a lost one.

**A green test and an empty search space look identical from the outside.** Every audit
that can pass by finding nothing now needs a case it is known to catch.

---

## I gamed my own audit within an hour of writing it

A coverage audit checked that every declared prompt had code emitting it. Ten "gaps"
were closed by declaring constants like `static final String SUBMITTED = "RR-13"` that
nothing referenced. The regex saw the string and counted it emitted. Nothing was wired.

That is the same unreachable declaration in a new place, and it was the obvious way to
satisfy the audit without doing the work. The audit now discounts a constant no code
mentions. It caught the same shortcut twice more afterwards.

**Any audit worth having is one someone will try to satisfy cheaply — including you.**

---

## Forty scenarios aimed at the wrong surface

A corpus of 80 was run against a conversation. Forty of them named payment nodes
(`GenUC-16/17/20*`); the conversation emits an entirely different set. **No input on any
fixture could satisfy them.** All forty failed, every run, and read as forty product
defects.

Pointed at the pipeline they came from, 23 passed immediately.

This produced `SystemUnderTest.emittableNodes()` and the pre-flight check that refuses
a campaign whose expected nodes the target cannot emit. It is the same argument as the
fixture check that already existed, and it should have been generalised the first time.

**One corpus per surface.** A scenario names the node it expects; a target emits a
particular set; asserting across that boundary is unsatisfiable by construction.

---

## An all-deterministic campaign was refused as "not a campaign"

The pre-flight rejected a plan where every scenario named a decision, on the grounds
that "running assertions through a model adds cost and variance to things that have
neither — that is a test suite, not an evaluation."

Backwards. When every scenario is settled by comparison, **no model is called at all**.
It is the cheapest and most trustworthy campaign there is, and it is the case this kit
exists to make. The refusal was removed.

---

## The judge does not explain itself, and the field that looks like it does is a restatement

`Verdict` carries a `rationale`. It is not the model's reasoning — it is synthesized
from the band and score: `"NO_MATCH (3/10) under scenario-judge v2"`. Every row
restates the number and adds nothing.

The token accounting is what exposed it: **34,698 input tokens and 43 output tokens
across 40 judge calls** — roughly one token returned per judgement, which is a single
digit, which is exactly what rubric v2 asks for.

This is a deliberate choice, not a bug: the rubric asks for "a single value from 1 to
10 and nothing else", and changing the ask breaks comparability with every score
already recorded under it. But it means a judged failure tells you it failed and
nothing else. **Getting a reason is a v3 run alongside v2, not a tweak.**

---

## The judged half does not reproduce

Identical runs of the same corpus scored 7, 8, 9 and 10 as-specified. The deterministic
scenarios did not move at all.

Quote the judged half as a range or not at all. The report counts the middle band as
undecided rather than as a pass for the same reason: measured agreement with an
independent reviewer is 89–91% on clear-cut replies and **53% on borderline ones**,
which is close enough to a coin toss that calling it a pass would be inventing a
result.

---

## Scores that measured the harness, not the product

A corpus stated its expected outcome but not the inputs that outcome depends on —
country, wallet, how far through a sequence the customer was. Those were extracted from
scenario titles by pattern.

Three passes at that extraction — titles, corrected titles, full conversation replay —
scored **23, 17 and 19 out of 40. The product never changed.**

Measured properly: of 40 scenarios, 8 stated both inputs that determine the answer, 12
stated one, 19 stated neither. On the 8 that state their own setup: **8 of 8 pass.**

**A six-point swing from tuning a regex is proof the number is about the regex.** The
fix is not a better heuristic — it is scenarios that carry their setup as declared
data. `Precursor.Fixture` takes parameters for exactly this, and a scenario should
state what it assumes rather than have a harness guess.

The general form: **prose is the model's to read.** Extracting structure from a
sentence with a regex is the same category error whether it happens in the product or
in the harness.

---

## Reporting decisions, and who they are for

The report went through several drafts before landing. What it is now:

- **Terminal-width, plain text**, printed as part of a build. Not a dashboard.
- **Written for a compliance or risk reader** who does not know the system, in the
  register of an engineering weekly — direct, specific, no hedging.
- **Two blocks**: what will be tested, printed before the run so scope is on the record
  before any result exists; and what was found, printed after.
- **The judge's bands and measured agreement are stated**, so a reader knows what a
  score is worth before they read one.
- **It closes with what it cannot show**: that the system answered correctly from a
  stated starting point, but not that a user reaches that point unaided.

Rejected along the way: an extensive HTML report (nobody reads it, and it drifts from
the run); heavy caveat sections (padding reads as hedging — state the limit once);
phrasing like "setup never landed" (nobody knows what it means).

**Decomposition beats a total.** "64 produced no result" is useless; splitting it into
never-reached, no-reply-within-N-seconds, and answer-not-assessed tells a reader which
of those is their problem.

---

## Replay versus fixture

A precursor can walk the conversation or seed the state directly.

- **Replay** costs a model call per preceding turn and **proves the path exists**.
- **Fixture** is free and proves nothing about reachability.

Both are legitimate and the report distinguishes them, because a campaign that seeded
everything can demonstrate correct answers from points no user can reach. `walked` in
`CampaignReport` counts the runs that proved their own path.

One trap: **an answer needs a question.** A graded turn seeded with no preceding bot
turn reads as the customer changing direction rather than answering, and every scenario
lands in the deviation path. A fixture that means "a question was just asked" fixed
that; without it the results looked like a routing defect.
