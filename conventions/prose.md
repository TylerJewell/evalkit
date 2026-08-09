# Prose rules

<!-- prose-audit: catalogue — this file quotes every construction it bans. -->

Read this file before writing any prose in this repository: documentation, specifications,
commit messages, Javadoc, README sections, and the analysis an assistant writes into a
terminal. Enforced by `tools/audit-prose.py`.

Write facts. Delete everything else. These rules apply to chat analysis exactly as they
apply to published documentation.

## Six tests, applied to every sentence as it is written

The banned list below is a blacklist, and a blacklist cannot be complete. Every sentence
can invent a shape nobody has named yet. These six tests are positive, they are short
enough to hold in your head, and they catch the shapes the list has not reached.

1. **One fact.** The sentence states one thing. Two facts in tension is antithesis. Three
   clauses joined by commas is a list wearing a sentence's clothes.
2. **Stands alone.** The sentence parses without the sentence before it and without the
   heading above it. "Partly." fails. "It counts only the runs that passed." fails. So does
   "Loading a file and hosting an agent do not.", which is grammatical and borrows its verb
   phrase from the sentence above.
3. **Adds a fact.** Delete the sentence and read the paragraph again. If nothing was lost,
   leave it deleted.
4. **Every number was counted.** A figure appears because someone measured it. Numbers
   chosen for rhythm are decoration that reads as evidence.
5. **Every term is earned.** A term of art is defined at the first use that carries weight,
   in the same sentence that uses it.
6. **Say it out loud.** Awkward diction survives silent proofreading and does not survive
   being spoken.

Apply these while writing. Applied afterwards they become a filter, and a filter passes
whatever it was not built to catch.

## Where the violations concentrate

Headings, figure captions, table labels, and the opening line of an answer. Short copy
rewards compression, and compression is where a quip forms. "Five inputs decide the
obligation set." "What backpressure needs to be real." "The grading is the hard part, not
the collection." "Each factor moves the answer." Every one of those is short, and every
one traded a fact for a shape.

Write the heading after the section it labels, and take it from the section. If a sentence
in the body already states the claim, use that sentence. A heading that had to be invented
is a heading that is about to perform.

## Re-read the paragraph after every edit

Roughly half the violations found in review were introduced while fixing other violations.
Removing a count orphans the sentence that referenced it. Replacing a noun with a pronoun
creates a lean. Tightening one sentence can strand the next. Re-read the whole paragraph,
never the sentence you changed.

## What a mechanical sweep is worth

A regex sweep matches shapes it has already been shown. It cannot hear diction, it cannot
tell a short complete sentence from a fragment, and it reports clean on prose that breaks
every rule in a way it has not met. Run it last, as a backstop. Passing it is not evidence.

## Banned constructions

Check every sentence against this list. Each of these reads as natural writing, which is
why the list exists.

**These are shapes, not words.** Grepping for the example phrases below passes prose that
breaks every rule. "The grading is the hard part, not the collection" contains no banned
word and is a banned construction. Read each sentence and name its shape.

**Comma-spliced clause chains.** Three or more independent clauses joined by commas into
one sentence: "A timeout produces a retry, the retry adds load to a system that is already
behind, and the added load produces more timeouts." It is a list wearing a sentence's
clothes. Break it into sentences, one idea each. A list of noun phrases after a colon is
fine; a chain of subject-verb clauses is not.

**Hedges and caveats.** "generally," "typically," "usually," "mostly," "often enough,"
"broadly speaking," and scope notes that soften a claim before anyone has challenged it.
We write specifics, so there is nothing to generalise about. If a claim needs a limit,
state the limit as a fact; do not apologise for the claim.

**Invented numbers used as rhythm.** "One team holds a shared understanding. Five teams
write it down. Fifty teams produce fifty interpretations." The counts are not data. Fake
precision built into an escalating triplet looks like evidence and is decoration. Use a
measured figure or describe the mechanism without quantifying it.

**Restatement that removes the fact.** "It runs inside the repositories, CI systems, and
AI coding assistants a team already uses. The check happens where changes already happen."
The second sentence drops the specifics the first supplied and survives on the symmetry of
its verbs. Delete it.

**Telling the reader about themselves.** "That gap is usually the reason the question was
asked." "If you are asking this, you have already hit it." The reader's motive is not a
fact we hold. State what is true about the system.

**Undefined terms of art.** A term the reader may not know is defined the first time it
carries weight, in the same sentence. "Grading is scoring each response against the
criteria you set" earns the later uses of "graded."

**A lede that names its subject without saying what it is.** "Each guide answers one
question about running agents in production." The reader has just arrived and does not
know what a guide is on this page. A lede either defines its subject or names something
the headline already established: "Each guide is a short answer to one engineering
question about taking an agent to production." The same failure with a term of art is
covered below; this one is about the ordinary noun the page is built around.

**A phrase written as a sentence.** "Questions that come up after the prototype
works." "What survives a restart, a deployment, or a three-day wait." "The runtime that
holds agent state." A noun phrase with a period on it has no verb of its own, so the
reader supplies one. This turns up most in ledes, blurbs and card captions, where the
phrase is doing a label's job. Give it a subject and a verb: "Each guide answers one
question that comes up after a prototype works." "Agent work has to survive a restart, a
deployment, and a wait that lasts days."

**Dangling references.** "Backpressure is the third." Third what? This appears after an
edit removes the antecedent. Re-read the paragraph after any change, not the sentence.

**Sentences that cannot stand alone.** Every sentence carries its own subject and reads
correctly when lifted out of the paragraph. Two failures produce this:

- *Fragment answers.* "Partly." "Rarely." "Memory." "No." "Up to 90%." "You do." A reply
  that only makes sense as a response to the heading above it. Name the subject:
  "Checkpointing solves part of it." "Memory fails first." "Developers keep writing code."
- *Pronoun openers with no antecedent in the sentence.* "It runs inside the repositories
  a team already uses." "It counts only the runs that passed." Replace the pronoun with
  the thing: "Akka Specify runs inside…", "Cost per verified task counts…".
- *Quantifiers carrying the subject.* "None of them requires a design change." "All of
  these run on the same substrate." "Each of them costs a model call." The sentence has a
  subject and a verb and names nothing, so the reader carries the referent forward. Name
  it: "None of the eleven items changes the Java code."

This matters most in answer blocks, FAQ answers, table cells, figure captions and
headings, because those are the fragments a search result or an answer engine quotes on
their own.

**Sentences that defer the explanation to a neighbour.** Each sentence below is
grammatically complete, so the fragment rules do not reach them. Read as a run they explain
nothing, because every one points at another sentence instead of carrying a fact:

> The split follows one test. A type belongs in evalkit-core when it decides what a run may
> claim. Scoring, outcome classification, pre-flight refusal and reporting all pass that
> test. Loading a file, running a test lifecycle and hosting an agent do not.

Three shapes produce that effect, and they travel together:

- *Announcement stubs.* A sentence whose only content is that an explanation is coming.
  "The split follows one test." "There are two reasons." "The rule is simple." "It works
  like this." "The mechanism is as follows." "One constraint decides it." Delete the stub
  and open with the fact: "A type belongs in evalkit-core when it decides what a run may
  claim."
- *Elided predicates.* A sentence closing on a bare auxiliary, where the verb phrase has to
  be carried over from the sentence before. "Loading a file, running a test lifecycle and
  hosting an agent do not." "The deterministic scenarios did not." Write the predicate out:
  "Loading a file, running a test lifecycle and hosting an agent decide nothing about what
  a run may claim."
- *Back-reference to a thing never named.* "…all pass that test" names a test the paragraph
  never introduced, because the paragraph introduced a criterion. Same failure in "this
  rule", "those cases", "the former", "the latter", "such a check". Repeat the noun that
  was actually established, or name the thing outright.

The test is whether a reader who stops after any one sentence has learned something. A
paragraph where the first sentence promises, the middle sentences qualify, and the last
sentence elides its verb has spent four sentences delivering one fact.

**Arguing for a choice inside documentation.** Documentation states what a thing is and how
it works. It does not argue that the choice was correct, and it does not narrate its own
reasoning. "Two further facts are verifiable and belong near the top." "This section covers
the module split." "The split works this way because a dependency-free core is worth
keeping." "That matters because a restart would otherwise lose the run."

Write the mechanism and stop:

> `CampaignPlan.check` reads the fixture names in the plan and compares them against
> `SystemUnderTest.fixtures()`. A plan naming a fixture the target does not declare returns
> `Check.Refused` with the missing names. No model call is made.

The reader needs the behaviour, the inputs, the outputs and the failure modes. Rationale
belongs in a limited part of the README and in product marketing, and nowhere else.

The banned forms: "which is why", "the reason for", "the rationale", "the argument for",
"this matters because", "it is worth noting", "by design", "is designed to", "makes the
case for", "this section covers", "belongs near the top", "as we saw above".

An incident is not rationale. "A content filter refused to score a transcript during
calibration" is an event with a date behind it, and a design document may report it. "The
design accounts for filters because they happen" argues, and does not.

**Abstractions given a purpose or an intent.** "Every capability exists to keep a figure
from being quoted beyond its evidence." "This type exists to enforce the split." "The
taxonomy separates a wrong answer from absent evidence." "The design ensures a restart
costs one wave." No engineer describing their own system says any of these out loud. The
subject is a concept rather than a thing that runs, and the verb credits it with an
intention.

Name the code and say what it does. `RunOutcome.NotReached` records a run whose precursor
never landed. `CampaignPlan.check` rejects a plan naming a fixture the target cannot build.
`CampaignWorkflow` writes a cursor to state after each wave, so a restart repeats one wave.

The banned forms:

- *`X exists to …` and `X exists because …`* Say what X does, or say what was happening
  before X was added. "Unscoreable was added after a content filter refused to score a
  transcript during calibration" reports an event; "Unscoreable exists because…" reports a
  purpose nobody witnessed.
- *`X is there to …`, `the reason X exists`, `what X is for`.* Same shape, longer.
- *Collective abstract subjects.* "every capability", "each feature", "all of the rules",
  "the whole design". A senior engineer names the class, the method, or the module.
- *Abstract nouns as agents.* "the design ensures", "the architecture guarantees", "the
  abstraction prevents", "the structure keeps". A design does not run. A method runs.

Write in the register a senior engineer uses in a design review: concrete subjects, the
name of the thing in the codebase, the observable behaviour, and the incident or
requirement that produced it.

**A phrase punctuated as a sentence.** "Same position." "Field for field." "New work."
"Mechanical." "No counterpart." Nineteen of these shipped in a published comparison table.
Ending a phrase with a full stop claims it is a sentence, so give it a subject and a verb:
"Both sit at the same point in the run." A table cell with no full stop is a label and needs
no verb.

**Courtroom vocabulary.** "what a run may claim", "absent evidence", "produced no evidence",
"the findings below stand", "quotable". An evaluation produces measurements, and these words
describe them as testimony. A run seeks no permission and a report survives no appeal. Say
"which numbers the report prints" and "produced no result". The same words reach the
compiler through `isEvidence()` and `isTrustworthy()`, where a caller cannot tell what the
method checks.

**A metaphor replacing a name that exists in the codebase.** "the ruler" for a `Rubric` that
carries a version. "band movement" for how many scenarios changed band. "pre-flight" for
`CampaignPlan.check`. Name the type or the method. A metaphor is defensible only where the
codebase supplies no name, and the existing type names `Lanes` and `wave` stay because the
prose follows the code.

**Register no engineer speaks.** "by construction", "carries the reasoning", "holds the
session", "is the point", "structural difference". Read each aloud in a design review. Say
"a red-team corpus is made of them", "the Javadoc explains the design", "keeps the session
id", and name the structure that differs.

**Antithesis.** Any "A is X; B is Y" or "not X, but Y" or "isn't just X — it's Y" shape.
Includes the softened forms: "these aren't edits, they're structural"; "the deck's subject
is AI, the note's subject is software"; "delivered in weeks, not quarters."
State the true half. Drop the contrast.

**Enumerate then collapse.** Counting things and then declaring them one thing: "three
surfaces, one platform"; "four moves, one story"; "N systems, one factory." The only
sanctioned instance is the canonical "one Platform, four Offerings." Invent no others.

**Counting abstractions instead of naming the thing.** "Two entry points," "three ways
in," "two paths," "four pillars," "N modes." Naming a count is not naming anything —
the reader still has to read on to learn what they are. Use the real names: GREENFIELD
AND MODERNIZATION, not TWO ENTRY POINTS. If the names do not fit, the section is
covering too much.

**Pithy numeric caption openers.** "Three moments.", "Two paths.", "One insight.", "Four
components.", "Three regions." — any figure caption, table caption, or callout that opens
with a numeric-plus-noun banner before the actual explanation. It is the same enumerate-
then-elaborate shape as "N X, one Y," compressed into two words. Open captions with the
declarative name of what is happening: "The recovery sequence." "The pair topology."
"The convergence table."

**Em-dash as punchline in headings and captions.** "Where an agent runs matters — and it
changes." "Checkout is different — it needs every replica to agree." "The Akka Agentic
Platform — where memory sits." Anywhere the em-dash sets up a payoff. State both sides
declaratively as separate sentences, or write one declarative sentence and drop the setup:
"Component placement across clusters and regions." "Checkout requires every replica to
agree." "The Akka Agentic Platform."

**Question or rhetorical headings.** "What the framework has to give you." "Why we built
it this way." "How the leader is chosen." Any heading that reads as an interview prompt
or a lead-in to prose. Headings state the fact the section will support: "Distributed-
systems primitives every agentic framework requires." "Rationale for the design." "Leader
derivation from entity id."

**Colour and emotion adjectives standing in for a claim.** "shiny," "elegant," "beautiful,"
"real," "genuine," "critical," "vital," "essential," "powerful," "robust," "modern." These
sound like descriptions and land as decoration. Name the concrete property: "the demos
that run on a laptop," not "the shiny demos"; "the mechanism the runtime relies on," not
"the critical mechanism."

**The-same-N pattern.** "The same five primitives," "the same three patterns," "the same
four questions." Reads as the enumerate-then-collapse structure ("N X → one thing") in a
slightly different dress. Either name the items, or drop the count.

**Softened antithesis via parallel negation.** "The shiny demos run on a laptop. The
production systems don't." "It works when the load is light. It doesn't when the load
grows." Same shape as "A is X; B is Y" — one sentence sets up, the next negates. State
the true condition once: "The demos run on a laptop but do not scale to production."
Better still, describe what production actually requires and skip the setup.

**Framework-lecture opener.** "It's very easy — and tempting — to look at…", "Before you
start…", "Take a hard look at…", "Ask yourself…". Second-person imperatives frame the
reader as needing correction. Write in the third person from the fact: "Assuming demos
can be taken to production is a common early mistake."

**Metaphor standing in for a plain word.** "load-bearing," "the spine," "the wedge" (in
customer-facing copy), "north star," "flywheel." Say what the thing does.

**"Bar" meaning a standard or a level.** "the quality bar," "holds at the bar," "meets the
bar," "raises the bar," "falls below the bar." The word names a physical object and is
being used for a number. Say the number, or say "threshold," "the score it has to reach,"
"the level the class already met."

**Hype verbs and AI-tells.** unlock, supercharge, leverage, harness, empower, revolutionize,
seamless, game-changer, journey, delve, tapestry, throughline, at its core, make no mistake,
it's worth noting, testament to, at the end of the day, this is where.

**Adjectives standing in for numbers.** "massive," "blazing-fast," "comprehensive,"
"sharpest," "strongest." Use the figure, or cut the claim.

**Rhetorical devices.** Colon-drama ("This is the real story: …"), rhetorical questions,
em-dash used as a punchline, sentence fragments for effect.

**Reader as the subject.** "Say you run 80 scenarios and 64 come back with a low score."
"You want to know whether the service is broken." "Score the empty reply and you get a low
number." A second-person subject makes the reader the actor in a story, invents a motive
nobody observed, and turns invented figures into evidence by putting them in a scenario.
Documentation states what the software does: "A campaign of 80 scenarios can return 64 low
scores for three unrelated reasons." A possessive is fine, because "your Akka download
token" names a real thing the reader owns.

**Ordinals standing for a thing never named.** "The first is the one you were testing for."
"The second is a setup failure." "The report leaves the last two out of the pass total." An
ordinal answers "which one" and names nothing, so the sentence is readable only while the
sentence that opened the list is still in view. Name the thing: "A setup failure produces
the same low score."

**Uncounted quantifiers as subjects.** "Most scenarios do not test a first message." The
sentence has a subject and a verb and reports no figure. Count it, or describe the class:
"A scenario that tests a first message is the exception in this corpus."

**Blame assigned to a program.** "The service takes the blame for the filter." "A number
that reads as a broken service." A run has no share of the fault and a number does not
read. Say which component produced the result: "Scoring that refusal as zero records a
filter decision against the service."

**Dangling participles.** "While calibrating this kit, a content filter refused to score a
transcript." The participle attaches to the subject that follows it, so this says the
filter was calibrating evalkit. Give the participle its own subject: "A content filter
refused to score a transcript during calibration."

**Elided complements.** "Models decline." "Judges disagree." The verb needs a complement to
carry the fact, and the sentence parses without one while reporting less than it appears
to. Say what: "A model returns a refusal instead of a score."

**Definitional epigrams.** "A number that moves that far when you edit a regex is a number
about the regex." Repeating the noun on both sides of the copula is a figure of speech, not
an explanation. State the finding: "The score moved by 6 points across three versions of
the regular expression, on a service that never changed."

**Unstated quantities.** "that far", "this much", "that often". A demonstrative in front of
a quantity asks the reader to carry a figure from an earlier sentence. Repeat the figure.

**Adjectives in front of a countable change.** "Small changes to the evaluation code move
the score." A change is counted in files or in lines. "Three versions of one regular
expression" says the same thing and can be checked.

**Overstatement about coverage.** "records the incident behind every design rule in the
codebase." Claiming every member of a set is a claim someone can falsify with one
counterexample. Say what the file holds: "records what went wrong before the rules were
written."

**Gerund subjects with the object dropped.** "Collecting may call a model." "Routing
happens before any call goes out." A transitive verb used as a gerund subject still needs
its object, and without one the sentence reads as an unfinished thought. Name it:
"Collecting the judgements may call a model." "Every scenario is routed before any call
goes out."

**Terms used before they are defined.** Every term of art gets a definition sentence
before any sentence that uses it, and the definition says what the thing *is* rather than
how it behaves. "A scenario names the state to start from, the message to send, and the
answer to expect" explains how a scenario works without ever saying that a scenario is one
test. Define first, then describe: "A scenario describes one test: an id, the state the
service has to be in before the test starts, the message to send, and the answer expected
back." This applies to every term the reader meets for the first time in a document, not
only to the first use in the repository.

**Arguing a property instead of stating it.** "compiles against the JDK and nothing else."
The "and nothing else" adds emphasis rather than information, which is how a claim sounds
when it is being defended. Itemise instead: "requires Java 21, declares no compile
dependencies, and uses JUnit 5 and AssertJ for its own tests." A reader can check an
itemised list and cannot check an emphasis.

## Required voice

- **Declarative.** Subject–verb–fact. One idea per sentence.
- **Top-down.** Lead each section with the claim, then the explanation.
- **Numbers over adjectives.** "5,000 onboardings per month," not "high volume."
- **No overstatement.** Never "none" when the truth is "limited." Concede what is real,
  then draw the precise line.
- **No insults.** Frame a competitor limitation as what the customer owns or inherits.
- **Bold sparingly.** One clause, never a sentence.

**Constructions nobody says out loud.** Read every sentence aloud. If you would not say
it to a colleague, do not write it. The recurring offenders:

- *Literary "hold" for "remain true."* "An unwritten convention holds inside the team."
  "A golden path holds while people follow it." "The savings hold as the base grows."
  Say "works," "applies," or "continues."
- *Cleft openers.* "What differs is how much work has to be done again." "What matters is
  where the state lives." Put the subject first: "Both runs fail at the same point. They
  differ in how much work has to be done again."
- *Broken correlatives.* "A style guide, a platform team, and a review board each help,
  and each has the same limit." The agreement breaks and the sentence stalls. Name the
  subject and say the thing: "Every one of those depends on a person remembering to
  apply it."
- *Verbs stretched past their meaning.* A physical or postural verb attached to something
  that cannot perform it: "a typical system **lands on** 30 to 60 controls," "the model
  **sits outside** that classification," "capacity **travels** upstream," "changes arrive
  faster than reviewers can **absorb** them," "each factor **moves** the answer." These
  read as casual and cost the reader the actual relationship. Say "is subject to,"
  "carries a lower risk tier," "reports," "review."

**Counts as labels.** A heading, figure caption, table label or section title never opens
with a count. "Six steps run before the work is live." "Five inputs decide the obligation
set." "Three regions." Counting is a tell, and it replaces the description the label owes
the reader. Describe what the thing is: "Every team builds the route before it builds the
feature." This holds even when the count is accurate and the items are drawn directly
below it.

## The test

Read each sentence and ask whether it delivers a fact or performs. Performances get
deleted, not softened. If a clause can be removed and the sentence still means the same
thing, remove it.

Then read it aloud. Awkward diction survives silent proofreading and does not survive
being spoken.

## Where each rule applies

| Surface | Rules in force |
|---|---|
| `docs/site/**` reference documentation | Every rule. State what a thing is and how it works. |
| `docs/specs/**` specifications | Every rule. |
| Javadoc and code comments | Every rule. A comment describes what is true now. |
| `README.md` | Every rule except the ban on arguing for a choice. The README is the one file that states rationale. |
| Commit messages | Every rule. Chronology belongs here and nowhere else. |

## Running the auditor

```shell
python tools/audit-prose.py docs/specs/*.html docs/site/evalkit/*.html.md
python tools/audit-prose.py --check-drift
```

Exit code 1 on any hit. `--check-drift` compares the rule bank against the constructions
named in this file. The auditor cannot hear diction, so a construction nobody has met yet
passes it. Read the prose aloud; the auditor is a backstop.

## Origin

These rules are a copy of the Akka house language rules maintained in the competitive
collateral repository at `.claude/skills/house-voice/SKILL.md`. The copy here keeps this
project buildable without that repository. The two copies drift.
