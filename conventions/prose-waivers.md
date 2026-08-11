<!-- prose-audit: catalogue -->

# Prose waivers

Wording the auditor flags and the project keeps. One entry per flagged phrase,
as `file | rule | phrase`, where the phrase is what the auditor prints under
`match`.

**This list may only shrink.** `--check-waivers` fails when an entry matches
nothing, because a waiver that outlives the wording it covers is a rule
switched off for a reason nobody remembers. Rewrite the sentence and delete the
entry in the same change.

## Waived

- `README.md | sentence cannot stand alone | It is similar to Pytest and DeepEval`
- `README.md | hedge adverb | easily`
- `README.md | reader as the subject | you can`
- `README.md | collective abstract subject | every decision`
