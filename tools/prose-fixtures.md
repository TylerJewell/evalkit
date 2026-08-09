# Prose auditor fixtures

<!-- prose-audit: catalogue — every line below is a banned construction, quoted so the auditor can be tested against it. -->

Every rule in `tools/audit-prose.py` is a check that passes when it finds nothing,
and a rule whose pattern never matches is indistinguishable from clean prose. Each
line below is wording that shipped in this repository and the rule that now catches
it. `python tools/audit-prose.py --self-test` reads this file, audits each line, and
fails when a rule stops firing.

Add a line here in the same change that adds a rule.

## Caught

- [hypothetical framing] Say you run 80 scenarios and 64 come back with a low score.
- [reader as the subject] You want to know whether the service is broken.
- [reader as the subject] Score the empty reply and you get a low number.
- [counting abstractions] Three different things produce a low number.
- [counting abstractions] The same problem turns up in two more places.
- [ordinal standing for a thing never named] The second is a setup failure.
- [ordinal standing for a thing never named] The report leaves the last two out of the pass total.
- [uncounted quantifier as subject] Most scenarios do not test a first message.
- [hedge] A scenario might need a signed-in customer with an open claim.
- [elided complement] A judge is a model reading a transcript, and models decline.
- [elided complement] Judges disagree.
- [dangling participle] While calibrating this kit, a content filter refused to score a transcript.
- [blame assigned to a program] Turn that refusal into a zero and the service takes the blame for the filter.
- [verb stretched past its meaning] Scoring an empty reply gives a number that reads as a broken service.
- [metaphor for plain word] At 53 percent a judge is close to a coin toss.
- [metaphor for plain word] The design history records the incident behind every design rule.
- [overstatement] The file records the reasoning for every rule in the codebase.
- [announcement stub] The same problem shows up during scoring.
- [adjective-for-number] Small changes to the evaluation code move the score.
- [vague verb for a named operation] The inputs were pulled out of the scenario titles by pattern.
- [unstated quantity] A number that moves that far is not measuring the service.
- [definitional epigram] A number that moves when a regex changes is a number about the regex.
- [argues for the choice] RunOutcome therefore carries a variant for each of them.

## Not caught

Wording the auditor reports clean, kept so a change that starts flagging it is
visible as a regression rather than as an improvement.

- The service never changed.
- CampaignPlan.check rejects a plan naming a fixture the target cannot build.
- A judge and an independent reviewer agreed on 53 percent of the replies scoring in the middle band.
- Run mvn install to build both modules.
- The Akka Specify plugin writes the repository into your settings file.
