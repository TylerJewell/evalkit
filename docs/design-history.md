# Design history

This file records the incidents that shaped evalkit. The rules in `CLAUDE.md` were
written after the failures below, and a failure persuades a reader who would argue with a
rule.

evalkit was built against one service, a post-travel claims chatbot with a corpus of
several hundred recorded scenarios, as a replacement for a third-party evaluation
product. That origin matters. The failures below are what a real corpus and a real
service did to the code, and none of them was imagined in advance.

---

## The runner reported a smaller total than it ran

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
`plan.scenarios()` in submission order. Under six lanes completion order and submission
order hold different scenarios at the same index. The per-scenario
CSV attributed results to the wrong requirement — visibly: a row whose `spec_node` was
`GenUC-16a.3` carried the detail "expected GenUC-17a".

**The lesson that generalises:** fixing a pairing hazard inside a class does nothing if
the type you hand back re-creates it. `Completed` is public and carries its `Scenario`
so there is nothing left to zip.

---

## A reply timeout was recorded as a service failure

**26 of 80 runs recorded as "never reached", because the reply timeout was too short.**

At six concurrent conversations against a live model, a 30-second reply timeout expired
before answers arrived. The report recorded those runs as the system failing to respond.

The ceiling is now generous and settable. A slow reply is a latency finding, and the
report names the timeout in the row so a reader can tell a slow answer from a missing
one.

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

Declaring an unreferenced constant is the same unreachable declaration in a new place,
and it was the obvious way to satisfy the audit without doing the work. The audit now
discounts a constant no code mentions, and it caught the same shortcut twice more
afterwards.

**Any audit worth having is one someone will try to satisfy cheaply — including you.**

---

## Forty scenarios aimed at the wrong surface

A corpus of 80 was run against a conversation. Forty of them named payment nodes
(`GenUC-16/17/20*`); the conversation emits an entirely different set. **No input on any
fixture could satisfy them.** All forty failed, every run, and read as forty product
defects.

Pointed at the pipeline they came from, 23 passed immediately.

This produced `SystemUnderTest.emittableNodes()` and the check in `CampaignPlan.check`
that refuses a campaign whose expected nodes the target cannot emit. The fixture check
already made the same argument, and it should have been generalised the first time.

**One corpus per surface.** A scenario names the node it expects. A target emits a
particular set of nodes. An assertion across that boundary can never pass.

---

## An all-deterministic campaign was refused as "not a campaign"

`CampaignPlan.check` rejected a plan where every scenario named a decision, on the
grounds that running assertions through a model adds cost and variance to a decision
that carries neither.

That reasoning is backwards. When every scenario is settled by comparison, **no model is
called at all**. Such a campaign is the cheapest and the most trustworthy one available,
and it is the case this kit was written to make. The refusal was removed.

---

## The judge does not explain itself, and the field that looks like it does is a restatement

`Verdict` carries a `rationale`, and the model never wrote a word of it. The field is
assembled from the band and the score: `"NO_MATCH (3/10) under scenario-judge v2"`.
Every row restates the number beside it and adds nothing.

The token accounting is what exposed it: **34,698 input tokens and 43 output tokens
across 40 judge calls** — roughly one token returned per judgement, which is a single
digit, which is exactly what rubric v2 asks for.

The bare score is a deliberate choice. The rubric asks for "a single value from 1 to
10 and nothing else", and changing the ask breaks comparability with every score
already recorded under it. The cost is that a judged failure reports its own failure and
supplies nothing a reader can act on. **Getting a reason takes a v3 run alongside v2.**

**v3 is that run.** The v3 rubric carries v2's band sentences word for word and adds two
labelled lines, and it loads beside v2, which keeps working unchanged. Whether the two
agree is a measurement nobody has taken, because it needs the reference corpus and that
corpus is not in this repository.

**The first thing a reason caught was the judge.** Four transcripts written to have
obvious answers were scored under both rubrics against a live model. One of them has an
agent that names the returns window and never refuses anything — "our return window is
30 days. Is there anything else?" — against an expected outcome of refusing and stating
the window. Both rubrics scored it **10, FAITHFUL**, twice, at temperature zero. v3 said
why: *"the agent's response in the simulation history perfectly refuses the refund by
stating the 30-day window"*. There is no refusal in that transcript.

The score was wrong under both rubrics and identically wrong. What changed is that under
v2 it is a bare 10, indistinguishable from the correct row above it, and under v3 the
claim is on the page and falsifiable against the transcript in seconds. **A reason does
not make a judge more accurate. A reason makes a judge auditable**, and auditability is
worth more here, because the 53 percent figure below says the scores were never going to
be trusted on their own.

---

## The judged half does not reproduce

Identical runs of the same corpus scored 7, 8, 9 and 10 as-specified. The deterministic
scenarios did not move at all.

Quote the judged half as a range or not at all. The report counts the middle band as
undecided for the same reason. Measured agreement with an independent reviewer is 89–91%
on clear-cut replies and **53% on borderline ones**, and calling a 53% agreement a pass
would invent a result.

---

## Scores that measured the extraction code

A corpus stated its expected outcome and left out the inputs that outcome depends on:
the country, the wallet, and the customer's position in a sequence. A regex extracted
those inputs from the scenario titles.

Three passes at that extraction — titles, corrected titles, full conversation replay —
scored **23, 17 and 19 out of 40. The product never changed.**

Measured properly: of 40 scenarios, 8 stated both inputs that determine the answer, 12
stated one, 19 stated neither. On the 8 that state their own setup: **8 of 8 pass.**

**A six-point swing from tuning a regex is proof the number is about the regex.** The
fix is scenarios that carry their setup as declared data. `Precursor.Fixture` takes
parameters for exactly this, and a scenario states what it assumes so that no code has
to guess.

The general form: **prose is the model's to read.** Extracting structure from a sentence
with a regex is the same category error in the product and in the code evaluating it.

---

## Reporting decisions, and who they are for

The report went through several drafts before landing. What it is now:

- **Terminal-width, plain text**, printed as part of a build. No dashboard was built.
- **Written for a compliance or risk reader** who does not know the system, in the
  register of an engineering weekly — direct, specific, no hedging.
- **Two blocks**: what will be tested, printed before the run so scope is on the record
  before any result exists; and what was found, printed after.
- **The judge's bands and measured agreement are stated**, so a reader knows what a
  score is worth before they read one.
- **The closing paragraph states what the run cannot show.** The system answered
  correctly from a stated starting point, and no part of the run shows that a user
  reaches that point unaided.

Rejected along the way: an extensive HTML report (nobody reads it, and it drifts from
the run); heavy caveat sections (padding reads as hedging — state the limit once);
phrasing like "setup never landed" (nobody knows what it means).

**Decomposition beats a total.** "64 produced no result" is useless; splitting it into
never-reached, no-reply-within-N-seconds, and answer-not-assessed tells a reader which
of those is their problem.

### What a report will not print

A reader would quote any of the figures below, and each one would mean something other
than it appears to mean:

- **One pass-rate percentage for the whole campaign.** The runs behind it are not the
  same kind of thing. Averaging a borderline model verdict with an exact match gives them
  equal weight, and the number has to pick which of the two to lie about.
- **An undecided result for a check settled by comparison or by a threshold.** A
  comparison matched or it missed. A number is inside its threshold or outside it.
  A comparison and a threshold carry no confidence to be borderline about, so the cell
  shows a dash. A figure in that cell reports a bug in this kit and says nothing about
  the service.
- **A zero for a run the judge would not score.** That reports a working service as a
  broken one.
- **A token total the service could not measure, printed as a measurement.** An
  unaccounted call makes the total a floor, and the report says so.
- **Any result at all when the service cannot satisfy the scenarios.** That campaign is
  refused before it runs.

The categories that belong together add up. The adding up is the whole of the guarantee,
and it is worth more than a headline number nobody can defend.

---

## What each outcome records

A low score by itself does not distinguish a wrong answer from a run that never asked the
question. Three situations produce a bad-looking result and only one of them is about the
service.

**The service answered and the answer was wrong.** `Scored`, `Asserted` and `Measured`
record a service that answered, whichever way the answer came out. `Scored`, `Asserted`
and `Measured` are the only outcomes that belong in a pass rate.

**The setup never completed.** A scenario needing a signed-in customer with an open claim
replays earlier turns or seeds the state directly, and a failure in that step means the
graded question was never asked. `NotReached` records it, and carries the cause so the
report can separate a setup that failed from a service that did not reply.

**The judge produced nothing to read.** During calibration a content filter refused to
score a `GenUC-02` identification-failure transcript. Code that dropped that run silently
would have reported better agreement than it earned, and code that scored it zero would
have reported a working service as a broken one. `Unscoreable` records it.

A metric that examined nothing lands here too, and that diverges from the upstream these
metrics were ported from. DeepEval scores a trace with no plan 1 and passes it, and
scores a run that made no tool call 1 for the correctness of arguments it never saw. Its
own documentation observes that a perfect plan score is a sign the reasoning was never
surfaced. Both defaults are the empty-search-space failure this repository has recorded
twice already, arriving through a metric where it previously arrived through an audit.

**A scorer threw an exception.** A metric that throws a `NullPointerException` produced
nothing either, and for a while that was filed under the same heading as a judge that
declined. The two record different facts. A declining judge is a property of the
transcript, and a throwing metric is a defect here. `ScorerFailed` records the second, so
a campaign whose unscored runs are all that variant shows a broken campaign where the
same count under `Unscoreable` would show a difficult corpus.

**Separating them has a trap.** The canonical `Unscoreable` — the content filter refusing
an identification-failure transcript — arrives as a *thrown exception*, because the judge
discovers it several frames deep in parsing a reply. Reclassifying every throw as a
scorer defect would have destroyed the exact case the variant was created for. Only the
code that threw knows whether it declined or broke, so `NoVerdict` says which, and the
runner reads the answer off the exception type. `RunOutcome.Cause` records a cause at the
site that knows it, and `NoVerdict` works the same way.

`RunOutcome` is sealed and its switches carry no `default`, so a further variant is a
compile error at every site that has to handle one. That cost is deliberate. Whoever adds
an outcome pays it, and a reader six weeks later never has to work out which column
absorbed it.

---

## Metrics that shipped with nothing to score

`PlanQuality` and `PlanAdherence` went in returning `Unscoreable` on every run, because
nothing a target reported carried a plan. Reporting the absence was honest and useless
together, because a metric whose absence path is the only path it can take reports the
same thing forever.

The field that fixes it already existed, in a record this kit could not see. An Akka
interaction record keeps every model call with the reasoning the provider returned
beside the answer, and an agent's plan sits in that reasoning and never in its reply.
DeepEval's own plan metric reads the same place. Reshaping `Evidence` on that record
gave the two metrics something to read, and two other limits fell out of the same
change: a recorded tool call carries what the tool returned, which closes the
comparison `ToolCorrectness` had documented as unsupportable, and per-call token counts
mean the report derives its unaccounted-call figure instead of asking the target to
confess one.

**The general form: a metric that reports absence forever is short of evidence, and a
cleverer metric will not fix it.** Ask first what the run could have recorded and did
not record.

---

## Replay versus fixture

A precursor can walk the conversation or seed the state directly.

- **Replay** costs a model call per preceding turn and **proves the path exists**.
- **Fixture** is free and proves nothing about reachability.

Replay and fixture are both legitimate, and the report distinguishes them, because a
campaign that seeded everything can demonstrate correct answers from points no user can
reach. `walked` in
`CampaignReport` counts the runs that proved their own path.

One trap: **an answer needs a question.** A graded turn seeded with no preceding bot
turn reads as the customer changing direction, and every scenario lands in the deviation
path. A fixture that means "a question was just asked" fixed that, and the results
before it looked like a routing defect.
