# The report, as panels

## What this specifies

The terminal report a campaign prints: which panels exist, what each one counts, the
words they use, and the rules that decide how they render at any size. `Panels` renders
it. The rules below name the tests in `PanelsTest` that hold them.

The rendered sample is in the README under "Reporting sample".

## The record is the artifact

The result of a run is a `RunRecord`. The report is a rendering of it.

Each figure is counted from the requirements in the record, and none is stored beside
them. A report whose total says 63 while its rows say 61 is unrepresentable. A
presentation also changes without running a campaign again, and a dataset is re-scored
without asking the system anything.

`CampaignReport` counts rows and stays as the durable campaign's tally. Once a campaign
repeats, 80 scenarios run five times each is 400 rows and 80 requirements. Only the
requirements reach the report.

## Vocabulary

One word per state, everywhere.

| Word | What it counts |
|---|---|
| passed | every run of the requirement passed |
| failed | every run failed |
| varied | some runs passed and some failed |
| undecided | the judge landed in its middle band |
| no result | no run produced an answer to score |

The report says `undecided` where the judge's bands are described, and says nothing about
a requirement being met.

A run that yielded nothing to score leaves a requirement's verdict to the runs that
settled. Varying is the system answering differently, so only runs that settled into a
pass or a failure are compared against each other.

## The panels

Panels appear when they have something to say, and are numbered in the order they appear.
A single-run report carries no gaps.

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

**The frame is fixed.** Plain ASCII, and no line runs past 80 columns at any dataset size.
Reports are piped to files, captured by CI and pasted into a bug tracker, and box drawing
and colour survive none of those.

**The confidence floor recomputes.** Panel 1 states what a clean sweep of the configured
repeat count establishes, using the exact binomial bound `alpha^(1/n)`. Every run passing
sits at the extreme of the distribution, where a normal approximation is least accurate
and reads high: it gives 57% at five runs where the exact bound gives 55%. The panel also
illustrates the range: a requirement the system handles eight times in ten still sweeps
five runs about a third of the time. Above a repeat count where that sweep falls below
one percent, the panel prints the range alone.

**The run strip is one column wide at any run count.** Twenty cells. Each covers
`ceil(n / 20)` runs. While a cell covers one run the mark is that run's result, spaced.
Above that the mark is `+` when more than half the runs in its slice passed, unspaced, and
the legend says what a mark covers. The row ends with `passes of runs`, because twenty
marks under a majority rule are reversible only to a range.

**Bars carry proportion and counts carry the figure.** Bars scale against the longest row,
and a count above zero always draws at least one cell. The figure beside each row is
exact.

**A measure nothing used is a row at zero.** Panel 4 lists each registered quality measure
in a fixed order, so the report says what was available as well as what was exercised. The
order runs outcome, authority, tool use, grounding, planning, efficiency.

**A missing measurement is stated.** A target that reports no timings says so, and the
panel draws no distribution.

**Latency is a distribution against the timeout.** Runs execute in lanes, so a run's wall
clock includes waiting for a free one, and a single average would describe the lanes as
much as the system. The panel names the lane count and says how many runs came close to
the timeout that would have turned them into no results.

## What is not settled

- Panel 2 prints every failure. A dataset with two hundred needs a cut-off and a pointer
  to the record.
- The near-miss threshold for a measured requirement is a constant that nothing derives.
- Panel 8 reports movement with no noise floor, so a small swing between runs may be
  sampling.
- Panel 8's row labels are `improved`, `regressed`, `unchanged`.
