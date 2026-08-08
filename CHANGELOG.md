# Changelog

Notable changes to evalkit. This project follows [semantic versioning](https://semver.org).

A rubric or metric version change is recorded here as well as in code, because
both change what a previously recorded score means.

## Unreleased

### Added

- `RunOutcome.Measured` for a result settled by computing a number against a
  threshold, with its own columns in `CampaignReport` and `RunSummary`.
- `Metric`, `Judgement` and `MetricRef`, splitting a metric into judgement
  collection and pure aggregation.
- `ToolPermission`, `TurnRelevancy`, `TurnFaithfulness` and
  `CitationFaithfulness`, ported from DeepEval with its own expected values.
- `Dag`, `DagNode` and `DagJudge`, a decision graph that calls a model only at
  branch points and keeps the score fixed in code.
- `Scorer` and `ScorerRouter`, routing each scenario to comparison, computation
  or a judge before any call goes out.
- `Recording`, `Evidence` and `ToolCall`, carrying what a run observed beyond
  the four fields a rubric interpolates.
- `Precursor.replay(String...)`, which the documentation already showed.
- A conformance suite pinning every ported metric to its upstream values, and a
  coverage test that fails when a ported metric has no fixture.

### Fixed

- `Verdict.parseScore` read `"3.5"` as `3`. A fractional score is now
  unreadable, where it was truncated before. Reading it as 3 instead of 4 moved
  a run from the undecided band into the failing one, on a rounding choice the
  judge never made.

### Changed

- `Scorer.score` takes a `Recording` in place of a `Transcript`, so a metric can
  read tool calls and latency while a judge's input stays unchanged.
- `SystemUnderTest.Reply` carries latency and tool calls.
- `CampaignReport` gained `measured` and `measuredPassed`. State written before
  those columns existed reads back with them at zero, pinned by
  `WorkflowStateSerializationTest`.
