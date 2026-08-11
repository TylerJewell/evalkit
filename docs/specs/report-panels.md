# The report, as panels

## What this specifies

The terminal report a campaign prints: which panels exist, what each one counts, the
words they use, and the rules that decide how they render at any size. `Panels` renders
it and `PanelsTest` holds every rule below.

The rendered sample is in the README under "Reporting sample".

## The record is the artifact

The result of a run is a `RunRecord`. The report is a rendering of it.

Every figure is counted from the requirements in the record, and none is stored beside
them. A whole class of defect — the total saying 63 while the rows say 61 — is therefore
unrepresentable rather than merely tested for. It also means a presentation can change
without running a campaign again, and a corpus can be re-scored without asking the system
anything.

`CampaignReport` counts rows and stays as the durable campaign's tally. The two count
different things once a campaign repeats: 80 scenarios run five times each is 400 rows
and 80 requirements. Only the requirements reach the report.

## Vocabulary

One word per state, everywhere.

| Word | What it counts |
|---|---|
| passed | every run of the requirement passed |
| failed | every run failed |
| varied | some runs passed and some failed |
| undecided | the judge landed in its middle band |
| no result | no run produced an answer to score |

`met` and `not met` are not used. `undecided` is band language and appears only where the
judge's bands are being described.

A run that produced no evidence does not make a requirement varied. Varying is the system
answering differently, not the harness falling over, so only runs that settled into a pass
or a failure are compared against each other.

## The panels

Panels appear only when they have something to say, and are numbered in the order they
appear. A single-run report is not a report with gaps in it.

| # | Panel | Appears when |
|---|---|---|
| 1 | What the run found | always |
| 2 | What failed | a requirement failed |
| 3 | The requirements that gave different answers between runs | repeats and something varied |
| 4 | How quality was measured | always |
| 5 | How the judge scored | a judge scored something |
| 6 | What this run cannot tell you | always |
| 7 | What it cost | always |
| 8 | Against the last run | a baseline exists under the same rubric and policy |

## Rules

**Frame.** Plain ASCII, no line past 80 columns, at any corpus size. Reports are piped to
files, captured by CI and pasted into tickets, and box drawing and colour survive none of
those.

**The confidence floor recomputes.** Panel 1 states what a clean sweep of the configured
repeat count establishes, using the exact binomial bound `alpha^(1/n)` rather than a
normal approximation: every run passing is the extreme of the distribution, where an
approximation is least accurate and always optimistic. It reads 57% at five runs where the
exact bound is 55%. The illustration — a requirement handled eight times in ten still
sweeping five runs about a third of the time — is dropped once a clean sweep stops being
something an unreliable requirement plausibly does, because past that it illustrates
nothing.

**The run strip is one column wide at any run count.** Twenty cells. Each covers
`ceil(n / 20)` runs. While a cell covers one run the mark is that run's result, spaced.
Above that the mark is `+` when more than half the runs in its slice passed, unspaced, and
the legend says what a mark covers. The row ends with `passes of runs`, because twenty
marks under a majority rule are reversible only to a range.

**Bars carry proportion, counts carry truth.** Bars scale against the largest row and
never render a non-zero count as nothing. The figure beside them is exact.

**A measure nothing used is a row at zero.** Panel 4 lists every registered quality
measure in a fixed order, so the report says what was available as well as what was
exercised. The order is outcome, then authority, tool use, grounding, planning, efficiency.

**Absent evidence is stated, never drawn.** A target that reports no timings says so
rather than rendering an empty distribution.

**Latency is a distribution against the timeout, never an average.** Runs execute in
lanes, so a run's wall clock includes waiting for a free one; a single figure would
measure the harness as much as the system. The panel names the lane count and says how
many runs came close to the timeout that would have made them no results.

## What is not settled

- Panel 2 prints every failure. A corpus with two hundred needs a cut-off and a pointer to
  the record.
- The near-miss threshold for a measured requirement is not yet derived from anything.
- Panel 8 reports movement with no noise floor, so a small swing between runs may be
  sampling rather than change.
- Panel 8's row labels are `improved`, `regressed`, `unchanged`.
