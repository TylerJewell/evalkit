# Security policy

## Reporting a vulnerability

Report a vulnerability through GitHub's private reporting, under the Security
tab of this repository. Do not open a public issue for a vulnerability.

Include what you did, what happened, and what you expected. A reproduction
against a fake `SystemUnderTest` is enough, and a real dataset is never needed.

## What a campaign holds

A campaign records conversations with a service under test. Those transcripts
can contain personal data, and they are written to `target/evalkit/` and to
whatever `ScenarioSource` reads.

Treat a dataset as production data. `.gitignore` excludes `target/`, and a
dataset committed to `src/eval/resources/datasets/` is committed to history.

## The judge reads adversarial text

A judge is a model reading a transcript, and a red-team dataset is made of
prompt-injection payloads. Those payloads reach the judge inside the transcript
it is asked to score.

A service running judges against recorded or live traffic should declare a
guardrail on `model-request` for its judge agents. A blocked call records
`Inconclusive`, which keeps a refused grade out of the pass total instead of
turning it into a low score.
