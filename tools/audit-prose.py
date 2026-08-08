#!/usr/bin/env python3
"""House-voice auditor for every prose artifact in the repo.

Scans HTML and Markdown for the constructions banned in
`.claude/skills/house-voice/conventions/prose.md`. Reports file, line, rule and matched
text. Exit code 1 on any hit at or above the failure threshold.

    python tools/auditors/voice/audit.py website/*.html
    python tools/auditors/voice/audit.py --context guide website/guides*.html
    python tools/auditors/voice/audit.py --context deck akka-overview/index.html
    python tools/auditors/voice/audit.py --format json --only-headings case-studies/*.html
    python tools/auditors/voice/audit.py --check-drift

Context matters. A question is the correct form for an FAQ heading and the
wrong form for a section heading, so rules carry the contexts they apply to
and `--context` selects them. Running without a context applies every rule,
which is right for a first pass and noisy for a page type that legitimately
uses one of the shapes.

What this cannot do: hear diction. "An unwritten convention holds inside the
team" and "a typical system lands on 30 to 60 controls" are caught here only
because those exact verbs are listed. A construction nobody has met yet passes.
Read the prose aloud; this is a backstop.
"""

import argparse
import json
import os
import re
import sys

if sys.stdout.encoding and sys.stdout.encoding.lower() != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    sys.stderr.reconfigure(encoding='utf-8', errors='replace')

ALL = ('web', 'guide', 'faq', 'blog', 'deck', 'case-study', 'battlecard', 'internal')
# Sales enablement, playbooks, and battlecard notes. Trade terms are precise
# there and vague in customer copy, so a few rules are scoped away from it.
CUSTOMER_FACING = tuple(c for c in ALL if c != 'internal')
# Surfaces that state what a thing is and how it works. Rationale belongs in a
# limited part of the README and in product marketing, so the rules against
# arguing for a choice are scoped away from web, deck and battlecard.
DOCUMENTATION = ('guide', 'faq', 'blog', 'case-study', 'internal')

# ── Rule bank ───────────────────────────────────────────────────
# (pattern, rule name, scope, contexts)
#   scope    : 'prose' | 'heading' | 'both'
#   contexts : tuple of contexts the rule applies to, or ALL

RULES = [
 # ── Antithesis ────────────────────────────────────────────────
 (r"\bisn['’]t\b[^.]{0,60}?\b(it['’]s|it is|but|—)\b", "antithesis (isn't X, it's Y)", 'both', ALL),
 (r"\baren['’]t\b[^.]{0,60}?\b(they['’]re|they are|but|—)\b", "antithesis (aren't X, they're Y)", 'both', ALL),
 (r"\bis not\b[^.]{0,60}?\bit is\b", 'antithesis (is not X, it is Y)', 'both', ALL),
 (r"\bnot only\b[^.]{0,40}?\bbut also\b", 'not-only-but-also', 'both', ALL),
 (r"[^.]{0,60}(, not | and not |, rather than | but rather |not just )[^.]{0,60}", 'antithesis (X, not Y)', 'both', ALL),
 # The comma-less form reads the same and slipped past the rule above.
 (r"[^.]{0,60}\brather than\b[^.]{0,60}", 'antithesis (X rather than Y)', 'both', ALL),
 (r"\b[A-Z][a-z]+ (does|is|runs|works)\b[^.]{0,80}?\.\s+[A-Z][a-z]+\s+(don['’]t|doesn['’]t|isn['’]t|aren['’]t)\b", 'parallel-negation antithesis', 'prose', ALL),

 # ── Counting ──────────────────────────────────────────────────
 (r"\bthe same (one|two|three|four|five|six|seven|eight|nine|ten|\d+)\s+\w+", 'the-same-N pattern', 'both', ALL),
 (r"\b(all|both|every)\s+(two|three|four|five|six|seven|eight|nine|ten|\d+)\s+\w+\s+in one\b", 'enumerate-then-collapse', 'both', ALL),
 (r"\b(one|two|three|four|five|six|seven|eight|nine|ten|\d+)\s+\w+,\s+(one|two|three|four|five)\s+\w+\b", 'N X, N Y collapse', 'both', ALL),
 (r"\ball (two|three|four|five|six|seven|eight|nine|ten)\s+\w+", 'count-then-collapse', 'both', ALL),
 (r"^(One|Two|Three|Four|Five|Six|Seven|Eight|Nine|Ten)\b", 'count as label', 'heading', ALL),
 # A count with no noun after it is the named things replaced by their number.
 # "the mechanism behind all four" makes the reader carry the list themselves.
 # Split by scope: a heading ends where the count ends, and a wrapped prose line
 # ends anywhere, so end-of-line counts as a boundary only in a heading.
 (r"\b(all|both|these|those|the)\s+(two|three|four|five|six|seven|eight|nine|ten)\b(?=\s*[.,;:)\]—-]|\s*$)",
  'bare count standing for the named things', 'heading', ALL),
 (r"\b(all|both|these|those|the)\s+(two|three|four|five|six|seven|eight|nine|ten)\b(?=\s*[.,;:)\]—-])",
  'bare count standing for the named things', 'prose', ALL),
 # A count before an abstraction replaces a name. A count before a unit is a
 # measurement, and measurements are required, so the noun decides.
 (r"\b(one|two|three|four|five|six|seven|eight|nine|ten)\s+(input|inputs|thing|things|step|steps|way|ways|reason|reasons|factor|factors|element|elements|point|points|mode|modes|path|paths|pillar|pillars|dimension|dimensions|option|options|stage|stages|layer|layers|outcome|outcomes|position|positions|choice|choices|category|categories|kind|kinds|type|types|aspect|aspects|area|areas|approach|approaches|consideration|considerations|consequence|consequences|advantage|advantages|benefit|benefits|difference|differences|property|properties|principle|principles|rule|rules|mistake|mistakes|failure|failures|trigger|triggers|signal|signals|theme|themes|lesson|lessons|move|moves|band|bands|claim|claims|measure|measures|criterion|criteria|shape|shapes|quality|qualities|virtue|virtues|takeaway|takeaways)\b", 'counting abstractions', 'both', ALL),

 # ── Hype and AI-tells ─────────────────────────────────────────
 (r"\b(unlock|supercharge|leverage|harness|empower|revolutionize|seamless|game-changer|delve)\b", 'hype verb', 'both', ALL),
 (r"\b(at its core|make no mistake|testament to|at the end of the day|worth noting|this is where)\b", 'AI-tell phrase', 'both', ALL),
 (r"\b(throughline|tapestry|journey)\b", 'AI-metaphor noun', 'both', ALL),
 (r"(?<!-)\b(shiny|elegant|beautiful|powerful|robust|modern|critical|vital|essential|amazing|incredible|remarkable)\b(?!-)", 'colour/emotion adjective', 'both', ALL),
 (r"\b(massive|blazing[- ]fast|comprehensive|sharpest|strongest|fastest|largest)\b", 'adjective-for-number', 'both', ALL),
 (r"\b(load-bearing|the spine|north star|flywheel|substrate)\b", 'metaphor for plain word', 'both', ALL),
 # "the wedge" names a known sales motion internally and is a metaphor to a customer.
 (r"\bthe wedge\b", 'metaphor for plain word', 'both', CUSTOMER_FACING),
 # "Bar" names a physical object and is used for a number.
 (r"\b(quality bar|the bar\b|a bar\b|bar that|bar for|raise[sd]? the bar|meets? the bar|"
  r"above the bar|below the bar|sets? the bar|clears? the bar|passes? the bar|holds? at the bar)",
  "'bar' meaning a standard or level", 'both', ALL),

 # ── Reader framing ────────────────────────────────────────────
 (r"\b(Take a hard look|Ask yourself|Before you start|Let['’]s face it)\b", 'framework-lecture opener', 'both', ALL),
 (r"\b(the reason (you|the question)|why you asked|if you['’]re wondering|you have already hit)\b", 'telling the reader about themselves', 'prose', ALL),
 # A buyer's motive is not a fact we hold. State what the system does.
 (r"\b(the reason|why)\s+(enterprises|customers|companies|buyers|teams|organi[sz]ations|people|they)\s+"
  r"(choose|chooses|buy|buys|pick|picks|select|selects|prefer|prefers|come|came|switch)\b",
  'unfalsifiable claim about a buyer motive', 'both', ALL),

 # ── Hedging ───────────────────────────────────────────────────
 (r"\b(usually|generally|typically|mostly|often enough|broadly speaking|somewhat|fairly|arguably)\b", 'hedge', 'both', ALL),
 # An adverb that softens a claim before anyone has challenged it. "cannot
 # easily match" concedes the claim in the act of making it: either the thing
 # can be matched or it cannot, and the adverb is there to avoid saying which.
 (r"\b(easily|simply|merely|largely|relatively|essentially|virtually|practically|"
  r"in most cases|for the most part|more or less|to some extent|tends? to)\b", 'hedge adverb', 'both', ALL),

 # ── Diction that fails read-aloud ─────────────────────────────
 (r"\bholds? (inside|while|as|true|good)\b", "literary 'hold' for 'remain true'", 'both', ALL),
 (r"\b(lands? on|sits? (outside|inside|above|below)|walks? through|leans? on|rides? on|travels? (back|upstream|forward)|moves? the answer|absorb (them|it|changes))\b", 'verb stretched past its meaning', 'both', ALL),
 (r"\bWhat (differs|matters|changes|follows|counts) is\b", 'cleft opener', 'both', ALL),
 (r"\b(each|both|either) [a-z]+ (help|have|carry|work|move|add|apply|decide)\b", 'broken correlative / agreement', 'both', ALL),

 # ── Structure ─────────────────────────────────────────────────
 # The em-dash heading is checked structurally below, because a label before the
 # dash ("Meeting 1 — Dana Whitfield, CISO") is a title and not a payoff.
 (r"^(What|Why|How|When|Where|Which|Who)\b[^?]{0,80}[.?]\s*$", 'rhetorical question heading', 'heading',
  ('web', 'blog', 'deck', 'case-study', 'battlecard')),
 (r"\bThis is the real story:", 'colon-drama', 'both', ALL),

 # ── Sentences that defer the explanation to a neighbour ────────
 # An announcement stub spends a sentence saying that an explanation follows.
 # Every one of these is grammatically complete, so the fragment checks below
 # never see them, and a run of them reads as explanation while carrying one
 # fact between four sentences.
 # Anchored at a sentence boundary, not a line start. A wrapped paragraph puts
 # most sentences mid-line, and a line-start anchor reports the first one only.
 (r"(?:^|(?<=[.!?])\s)(The|Its?|Our|This|That)\s+\w+\s+(is|are)\s+"
  r"(simple|straightforward|this|as\s+follows|the\s+same)\b",
  'announcement stub', 'prose', ALL),
 # Spelled-out small numbers only. A measured figure is written in digits.
 (r"(?:^|(?<=[.!?])\s)There (is|are)\s+(one|two|three|four|five|six|seven|eight|nine|ten)\s+\w+",
  'announcement stub', 'prose', ALL),
 (r"\b(follows|passes|fails|meets|comes down to)\s+(one|a single|the same)\s+"
  r"(test|rule|criterion|standard|question|principle|idea|thing)\b",
  'announcement stub', 'prose', ALL),
 (r"(?:^|(?<=[.!?])\s)(One|A single)\s+\w+\s+"
  r"(decides|settles|explains|drives|governs|answers)\s+(it|this|that)\b",
  'announcement stub', 'prose', ALL),
 (r"\b(works|goes|reads|breaks\s+down)\s+like\s+this\b", 'announcement stub', 'both', ALL),
 (r"\bis\s+as\s+follows\b", 'announcement stub', 'both', ALL),
 # A pointer standing in for the thing it points at.
 (r"\bthe (former|latter)\b", 'back-reference to a thing never named', 'both', ALL),

 # ── Courtroom vocabulary ───────────────────────────────────────
 # An evaluation produces measurements. Describing them as testimony makes a
 # software reader translate: a run has no permission to seek and no ruling to
 # survive. Full list in docs/specs/prose-audit-list.html.
 (r"\bmay claim\b|\bpermitted to claim\b|\ballowed to claim\b", 'courtroom vocabulary', 'both', ALL),
 (r"\babsent evidence\b|\bproduced no evidence\b|\bno evidence (about|for)\b",
  'courtroom vocabulary', 'both', ALL),
 (r"\bfindings? (below|above)?\s*stands?\b", 'courtroom vocabulary', 'both', ALL),
 (r"\b(not\s+)?quotable\b", 'courtroom vocabulary', 'both', ALL),

 # ── Metaphor replacing a name in the codebase ──────────────────
 (r"\b(the|one|a) ruler\b", 'metaphor for a named type', 'both', ALL),
 (r"\bband movement\b", 'metaphor for a named type', 'both', ALL),
 (r"\bpre-?flight\b", 'metaphor for a named type', 'both', ALL),
 (r"\ba floor rather than\b", 'metaphor for a named type', 'both', ALL),

 # ── Register no engineer speaks ────────────────────────────────
 (r"\bby construction\b", 'register no engineer speaks', 'both', ALL),
 (r"\bcarr(y|ies|ying) the (reasoning|argument|thinking)\b",
  'register no engineer speaks', 'both', ALL),
 (r"\bholds? the (session|reference|state|lock)\b", 'register no engineer speaks', 'both', ALL),
 (r"\bis the point\b", 'register no engineer speaks', 'both', ALL),
 (r"\bstructural difference\b", 'names no structure', 'both', ALL),
 (r"\bcontribution standard\b", 'invented project vocabulary', 'both', ALL),

 # ── Project vocabulary ─────────────────────────────────────────
 # GitHub names the parts of a project: issue, pull request, milestone, label,
 # release, discussion, branch, commit, tag. An invented synonym makes the
 # reader translate before they can act, and "known gap" names an issue.
 (r"\bknown[- ]gaps?\b", 'invented project vocabulary', 'both', ALL),
 (r"\bgaps?\s+(list|register|log|analysis)\b", 'invented project vocabulary', 'both', ALL),
 (r"\b(punch|wish|laundry)\s+list\b", 'invented project vocabulary', 'both', ALL),
 (r"\bticket(s|ed)?\b", 'invented project vocabulary', 'both', ALL),

 # ── Arguing for a choice inside documentation ──────────────────
 # Documentation states what a thing is and how it works. Rationale belongs in
 # a limited part of the README and in product marketing. Scoped away from
 # 'web', 'deck' and 'battlecard', which are the surfaces that argue by design.
 (r"\bwhich is why\b", 'argues for the choice', 'both', DOCUMENTATION),
 (r"\bthe (reason|rationale|argument|case)\s+(for|behind|why|that)\b",
  'argues for the choice', 'both', DOCUMENTATION),
 (r"\b(this|that|it)\s+(matters|is important)\s+because\b", 'argues for the choice', 'both', DOCUMENTATION),
 (r"\b(it'?s|it is)\s+worth\s+(noting|stating|saying|remembering)\b",
  'argues for the choice', 'both', DOCUMENTATION),
 (r"\bby design\b", 'argues for the choice', 'both', DOCUMENTATION),
 (r"\b(is|are|was|were)\s+designed\s+to\b", 'argues for the choice', 'both', DOCUMENTATION),
 (r"\bmakes? the case\b", 'argues for the choice', 'both', DOCUMENTATION),
 # Narrating the document instead of writing it.
 (r"\bthis\s+(section|document|page|chapter|table)\s+"
  r"(covers|describes|explains|lists|shows|sets out|walks through)\b",
  'meta-commentary about the document', 'both', DOCUMENTATION),
 (r"\bbelongs?\s+(near|at)\s+the\s+top\b|\bbelongs?\s+in\s+(this|the)\s+(document|README|section)\b",
  'meta-commentary about the document', 'both', DOCUMENTATION),
 (r"\bas (we|you) (saw|noted|covered)\b", 'meta-commentary about the document', 'both', DOCUMENTATION),
 # Narrating the artifact on the page instead of letting it speak.
 (r"\bthe\s+(block|table|list|example|sample|figure|diagram|snippet|code)\s+"
  r"(below|above|that follows|shown here)\b",
  'meta-commentary about the document', 'both', DOCUMENTATION),

 # ── Abstractions given a purpose or an intent ──────────────────
 # "Every capability exists to keep a figure from being quoted" has a concept
 # for a subject and credits it with an intention. A senior engineer names the
 # class or the method and says what it does when it runs.
 (r"\bexists?\s+(to|so\s+that|because|for)\b", 'abstraction given a purpose', 'both', ALL),
 (r"\b(is|are|was|were)\s+there\s+to\b", 'abstraction given a purpose', 'both', ALL),
 (r"\bthe\s+reason\s+\w+\s+exists?\b", 'abstraction given a purpose', 'both', ALL),
 (r"\b(every|each|all\s+(of\s+)?the)\s+(capabilit(y|ies)|features?|rules?|decisions?|"
  r"choices?|behaviours?|behaviors?|mechanisms?|abstractions?)\b",
  'collective abstract subject', 'both', ALL),
 (r"\bthe\s+(design|architecture|taxonomy|abstraction|structure|framework|split)\s+"
  r"(ensures?|guarantees?|prevents?|allows?|enables?|separates?|keeps?|makes?)\b",
  'abstract noun as agent', 'both', ALL),
]

# Sentences that cannot be read on their own. Checked structurally rather than
# by pattern, because the failure is the missing subject.
#   "It runs inside the repositories" opens on a pronoun with no antecedent.
#   "This table is the spine" opens on a determiner and a noun, and reads alone.
# "It" and "They" are always pronouns. The rest are determiners too, so they
# only fail when a verb follows and the word is carrying the subject by itself.
BARE_PRONOUN = re.compile(r"^(It|They)\s+[a-z]")
BARE_DEMONSTRATIVE = re.compile(
    r"^(That|This|These|Those|Both|Either|Neither|Such)\s+"
    r"(is|are|was|were|has|have|had|means?|makes?|gives?|leaves?|comes?|goes|"
    r"runs?|works?|happens?|follows?|applies|apply|matters?|counts?|comes|comes)\b")


# A quantifier carrying the subject by pointing at the sentence before it.
# "None of them requires a design change." is grammatical, has a subject and a
# verb, and names nothing. The demonstrative pattern above misses it because
# the head word is followed by "of" rather than by a verb.
QUANTIFIER_SUBJECT = re.compile(
    r"^(None|All|Each|Any|Some|Many|Most|Few|Several|Both|Either|Neither|"
    r"One|Two|Three|Four|Five|Six|Seven|Eight|Nine|Ten)\s+of\s+"
    r"(them|these|those|it|us|the\s+\w+)\b", re.I)


def bare_subject(sent):
    return bool(BARE_PRONOUN.match(sent) or BARE_DEMONSTRATIVE.match(sent)
                or QUANTIFIER_SUBJECT.match(sent))

# Wording that reads as a banned construction and is a verifiable fact.
# A superlative about a named entity is a claim with a source behind it, not an
# adjective doing a number's job: the LHC is the world's largest machine.
# "mission-critical" is a category of system, not a colour word.
EXEMPT = re.compile(
    r"\bmission[- ]critical\b"
    # A possessive proper noun before a superlative is a sourced market claim:
    # "Canada's largest virtual communities", "the world's largest machine".
    r"|\b(?:[A-Z][\w.&-]*|world|group|company)(?:'s|&rsquo;s|&#x27;s)\s+larges"
    r"|largest\s+(?:shareholder|factory\s+networks?|machine|independent)"
    r"|one of the largest\b"
    # A counted superlative selects a set by measurement: "the two largest
    # distribution centres" names which two, and the count is the criterion.
    r"|\b(?:the\s+)?(?:two|three|four|five|six|\d+)\s+larges"
    # A score in a rubric. "two points" is a unit, and units are measurements.
    r"|\b(?:zero|one|two|three|four|five|\d+)\s+points?\b"
    # "harness" and "leverage" are hype as verbs and ordinary nouns here: an
    # agent harness is a piece of software, and model leverage is a named
    # property of Akka Optimize.
    r"|\b(?:third-party|agent|evaluation|test|coding|AI)\s+harness"
    r"|harness(?:es)?\s+(?:such as|like|that|the customer)"
    r"|\b(?:model|gives|more|market)\s+leverage\b")
FRAGMENT_ANSWER = re.compile(r"^(Partly|Rarely|Yes|No|Maybe|Sometimes|Memory|Both|Neither|Correct)\.?$", re.I)

# A sentence closing on a bare auxiliary borrows its verb phrase from the
# sentence before it. "Loading a file, running a test lifecycle and hosting an
# agent do not." is grammatical, carries a subject, and still cannot be read on
# its own, so neither the fragment check nor the bare-subject check reaches it.
ELIDED_PREDICATE = re.compile(
    r"\b(do|does|did|is|are|was|were|has|have|had|can|could|will|would|should|must|may)"
    r"(\s+not|n't)?\s*[.!]$", re.I)

# A demonstrative naming an abstraction the paragraph never introduced.
# "…all pass that test" points at a test, and the paragraph named a criterion.
# Restricted to the nouns that carry this failure, because "that entity" after
# a paragraph about entities is ordinary reference and reads correctly.
ABSTRACT_REFERENT = (
    'test', 'rule', 'criterion', 'standard', 'principle', 'check', 'distinction',
    'difference', 'property', 'constraint', 'condition', 'requirement', 'mechanism',
    'pattern', 'shape', 'split', 'choice', 'decision', 'tradeoff', 'trade-off',
    'approach', 'problem', 'failure', 'effect', 'reason', 'point')
DEICTIC = re.compile(
    r"\b(?:that|this|those|these|such an?)\s+(%s)s?\b" % '|'.join(ABSTRACT_REFERENT), re.I)


def unbound_deictic(block):
    """Demonstratives pointing at an abstraction the block never named.

    The noun is looked for in the text preceding the match, so a demonstrative
    that refers back to something already introduced passes. Scoped to one
    block: a referent established two paragraphs earlier is too far away to
    carry, which is the same reasoning the parallel-frame check uses.
    """
    out = []
    for m in DEICTIC.finditer(block):
        noun = m.group(1).lower()
        if re.search(r"\b%ss?\b" % re.escape(noun), block[:m.start()], re.I):
            continue
        out.append(m.group(0))
    return out

# Independent clauses chained with commas into one sentence.
CLAUSE_VERB = re.compile(
    r"\b(is|are|was|were|has|have|had|does|do|did|can|will|would|should|must|"
    r"runs?|takes?|makes?|gets?|holds?|keeps?|stays?|adds?|produces?|becomes?|needs?|costs?|"
    r"carries|carry|falls?|decides?|drives?|shares?|sets?|shows?|reaches?|covers?|compiles?|"
    r"contributes?|writes?|reads?|moves?|stops?|starts?|leaves?|arrives?|depends?|means?|"
    r"operates?|detects?|recovers?|bills?|trains?|learns?|teaches?|builds?|gives?|inherits?|"
    r"provides?|supplies|supply|prices?|names?|answers?|judges?|solves?|propagates?)\b", re.I)


# A short phrase punctuated as a sentence. "Same position." "New work."
# "Field for field." "Mechanical." Nineteen of these shipped in a published
# comparison table, because the fragment checks above look for a relative
# clause or a pronoun opener and find neither.
#
# Deciding in general whether a sentence has a main verb needs a parser, and a
# verb list cannot substitute: a first attempt at this check listed 80 verbs and
# reported 508 hits across the collateral, nearly all of them real sentences
# whose verb was absent from the list ("Akka owns the SLA", "GDPR Chapter V
# governs transfers"). The bounded form below reports only when no word in the
# phrase could inflect as a finite verb, and only up to four words, so a
# sentence with any verb-shaped word passes whether or not the verb is known.
INFLECTED = re.compile(r"\w+(s|ed|ing|n't)$", re.I)
FINITE_MARKER = re.compile(
    r"\b(is|are|was|were|be|been|am|has|have|had|do|does|did|can|could|will|would|"
    r"shall|should|may|might|must|cannot|won|don|doesn|isn|aren|hasn|haven)\b", re.I)
IDENTIFIER = re.compile(r"^[A-Za-z_][\w.$]*(\(\))?$")

# An imperative opens on a bare verb, which carries no inflection to detect.
# "Cite the source." and "Concede first." are sentences and instructions are
# written that way, so the opening word is checked against this closed set.
# Only the first word is tested, so a miss costs one unreported fragment.
IMPERATIVE = frozenset("""
add adopt apply ask assert assign attribute audit avoid break budget build
call cap capture change check choose cite claim classify close collect commit
compare concede confirm connect consider count create declare define delete
deliver deploy derive describe design detect do document draft drop emit
enable enforce ensure escalate evaluate exclude expect explain export extend
fetch find fix flag follow gate generate give go group guard handle hold
ignore implement import include inspect install invoke isolate keep label lead
let limit link list load log look make map mark match measure merge mock
monitor move name note open own pass pick pin plan poll prefer prevent price
print prove publish put query quote raise rank rate read record reduce refuse
reject release remove rename render repeat replace report request require
reset resolve restore retry return reuse review route run sample say scale
schedule scope score see seed select send separate serve set share ship show
sign skip sort split stage start state stop store submit support surface
tag take target tell test throw trace track treat trigger try tune turn
update use validate verify wait watch wrap write
""".split())

# A period after a number, inside a citation, or after an abbreviation is not a
# sentence boundary. "EU AI Act Art." is a citation cut in half by the splitter.
CITATION_NOISE = re.compile(r"^[\d(),;%&#\s]|[;%)]")
ABBREVIATION = frozenset("""
art sec fig no vs eg ie etc inc ltd co corp dept est approx vol ch pp ver rev
""".split())


def punctuated_without_verb(sent):
    text = sent.strip()
    if not text.endswith('.') or text.endswith('...'):
        return False
    words = text.rstrip('.').split()
    if not (1 <= len(words) <= 4):
        return False
    if CITATION_NOISE.search(text):
        return False
    if words[0].strip('*_`').lower() in IMPERATIVE:
        return False
    if words[-1].strip('*_`').lower() in ABBREVIATION:
        return False
    if any(IDENTIFIER.match(w) and ('.' in w or '(' in w) for w in words):
        return False                      # a cell naming a method or a type
    if FINITE_MARKER.search(text):
        return False
    return not any(INFLECTED.match(w) for w in words)


SUBORDINATOR = re.compile(r"^\s*(what|when|which|that|how|who|where|while|because|if|although|after|before|since|unless|and how|and what|and where|to)\b", re.I)
OPENING_SUB = re.compile(r"^\s*(when|while|because|if|although|after|before|since|unless)\b", re.I)


# A noun phrase closed with a period. It reads as a sentence and has no verb of
# its own, so the reader has to supply one.
#
# One shape only. Deciding in general whether a sentence has a main verb needs a
# parser: a wh-opener is a fragment in "What survives a restart." and a complete
# sentence in "Where progress is stored decides what survives.", and nothing
# short of parsing separates them. The bounded case below does not need the
# verb identified, so it is the one that is enforced.
NP_RELATIVE = re.compile(r"^([A-Z][\w'’-]*(?:\s+[\w'’-]+){0,2})\s+(that|which|who)\s+(\S+)")

# "that" is a relative pronoun before a verb and a determiner before a noun.
# "Akka replaces that stack with shared compute." is a sentence; the same
# pattern with a verb after "that" is a noun phrase.
VERB_AFTER_REL = re.compile(
    r"^(?:[a-z]+(?:s|ed|ing)|come|go|run|make|take|hold|keep|need|cost|arrive|happen|"
    r"fail|pass|stop|move|read|write|have|has|had|is|are|was|were|can|will|must|do|did)\b")


def is_fragment(sent):
    """A noun phrase with a period on it, modified only by a relative clause.

    Bounded at eight words. Past that the tail is long enough to be carrying a
    main verb, which made "A task that restarts from the beginning pays for its
    completed steps twice." read as a fragment.
    """
    if sent.endswith('?') or not 4 <= len(sent.split()) <= 8:
        return False
    m = NP_RELATIVE.match(sent)
    return bool(m and not CLAUSE_VERB.search(m.group(1))
                and VERB_AFTER_REL.match(m.group(3)))


LEADING_CONJ = re.compile(r"^\s*(and|but|or|then|so)\s+", re.I)


def _independent(part):
    """A clause with a subject of its own.

    "calls a model" opens on its verb, so it shares the previous subject and is
    one predicate in a compound, not a clause in a chain.
    """
    rest = LEADING_CONJ.sub('', part).strip()
    if SUBORDINATOR.match(rest):        # "which supplies the runtime" modifies, it does not chain
        return False
    return bool(CLAUSE_VERB.search(rest)) and not CLAUSE_VERB.match(rest)


def is_comma_splice(sent):
    """Three or more independent clauses joined by commas.

    A colon introduces a list, and a series of subordinate wh-clauses is a
    single predicate rather than a chain, so neither counts.
    """
    if len(sent) <= 60 or ':' in sent:
        return False
    parts = sent.split(', ')
    if len([c for c in parts if _independent(c)]) < 3:
        return False
    if OPENING_SUB.match(sent):
        return False
    return len([c for c in parts if SUBORDINATOR.match(c)]) < 2


# Each Q&A item, matched individually so a class list like "qa rv" still counts.
FAQ_BLOCK = re.compile(r'<div class="[^"]*\b(?:q|qa|faq)\b[^"]*"[^>]*>.*?</div>', re.S)


def _in_faq(text, pos):
    return any(m.start() <= pos <= m.end() for m in FAQ_BLOCK.finditer(text))


# A tag name written inside a stylesheet comment opens a match that runs to the
# next real closing tag, swallowing the CSS in between and scoring it as prose.
# Newlines are preserved so reported line numbers still point at the source.
NON_PROSE = re.compile(r"<(style|script)\b[^>]*>.*?</\1>|<!--.*?-->", re.S | re.I)


def strip_non_prose(text):
    return NON_PROSE.sub(lambda m: '\n' * m.group(0).count('\n'), text)


# A comment inside a code sample is prose, and it was reaching readers
# unexamined: blocks() yields only the prose-bearing elements, so nothing
# inside <pre> or a fenced block was ever checked. "One transcript, three
# judges, three lenses on the same rubric family." is a noun phrase with a
# period on it and shipped in a published sample.
CODE_BLOCK = re.compile(r"<pre\b[^>]*>(.*?)</pre>|^```[^\n]*\n(.*?)^```", re.S | re.M)
LINE_COMMENT = re.compile(r"(?:^|\s)(?://|#)\s+(.+?)$", re.M)
BLOCK_COMMENT = re.compile(r"/\*+(.*?)\*/", re.S)


def code_comments(text):
    """Yield (line, comment text) for every comment inside a code sample.

    Tag markup is removed first, so a highlighted comment reads as its words.
    A comment shorter than four words is a label rather than a sentence, and
    the sentence rules do not apply to it.
    """
    for m in CODE_BLOCK.finditer(text):
        body = m.group(1) or m.group(2) or ''
        base = text[:m.start()].count('\n')
        body = re.sub(r'<[^>]+>', '', body)
        body = body.replace('&lt;', '<').replace('&gt;', '>').replace('&amp;', '&')
        for cm in LINE_COMMENT.finditer(body):
            said = cm.group(1).strip()
            if len(said.split()) >= 4:
                yield base + body[:cm.start()].count('\n') + 1, said
        for cm in BLOCK_COMMENT.finditer(body):
            said = re.sub(r'\s*\*\s*', ' ', cm.group(1)).strip()
            if len(said.split()) >= 4:
                yield base + body[:cm.start()].count('\n') + 1, said


FENCED = re.compile(r"^```.*?^```", re.S | re.M)


def strip_fenced(text):
    """Blank the inside of fenced blocks, keeping newlines so lines still count.

    A fenced block holds code or program output, and neither is prose. A sample
    report pasted into a README was reporting its own lines as sentences that
    cannot stand alone. Comments inside the fence are still audited, because
    code_comments() reads the unstripped text.
    """
    return FENCED.sub(lambda m: '\n' * m.group(0).count('\n'), text)


def blocks(text, path):
    """Yield (line, kind, sentence-bearing text) for prose-bearing elements."""
    if path.lower().endswith(('.md', '.markdown')):
        text = strip_fenced(text)
        # Headings are single lines. Prose is wrapped, so it is yielded as whole
        # paragraphs: a pattern bounded by sentence punctuation cannot match
        # across a line break, and rules were silently passing on wrapped copy.
        for i, raw in enumerate(text.split('\n'), 1):
            s = raw.strip()
            if s.startswith('#'):
                yield i, 'heading', s.lstrip('# ').strip()
        for i, para in paragraphs(text, path):
            if len(para) > 3:
                yield i, 'prose', para
        return
    # td and th were absent until 2026-08-08, so no HTML table cell had ever
    # been checked, while the rules name table cells as a place violations
    # concentrate. A markdown table row reaches the engine as ordinary prose.
    for m in re.finditer(r'<(p|h[1-4]|figcaption|blockquote|li|dd|caption|td|th)(?:\s[^>]*)?>(.*?)</\1>', text, re.S):
        tag = m.group(1).lower()
        s = re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', '', m.group(2))).strip()
        if len(s) < 4:
            continue
        if tag.startswith('h') or tag in ('figcaption', 'caption'):
            # A question is the correct form for an FAQ heading, so classify by
            # position instead of relying on the caller to pass the right context.
            kind = 'faq-heading' if _in_faq(text, m.start()) else 'heading'
        else:
            kind = 'prose'
        # A customer said this. It is evidence, not copy, and rewriting it
        # would misquote them.
        if kind == 'prose' and re.match(r'^\s*(?:"|&ldquo;|&quot;|“)', s):
            kind = 'quote'
        yield text[:m.start()].count('\n') + 1, kind, s
    # Text inside a diagram is copy like any other, and it is where counts and
    # summary sentences hide from a rule bank that only reads HTML elements.
    for m in re.finditer(r'<text\b[^>]*>(.*?)</text>', text, re.S):
        s = re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', '', m.group(1))).strip()
        if len(s) >= 12:
            yield text[:m.start()].count('\n') + 1, 'diagram', s
    # Divs the guide and efficiency templates use for captions, answers and cells.
    for cls, kind in (('fig-note', 'heading'), ('area-blurb', 'prose'),
                      ('answer', 'prose'), ('sources', 'prose')):
        for m in re.finditer(r'<div class="%s"[^>]*>(.*?)</div>' % cls, text, re.S):
            s = re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', '', m.group(1))).strip()
            if len(s) >= 4:
                yield text[:m.start()].count('\n') + 1, kind, s


def sentences(s):
    return [x.strip() for x in re.split(r'(?<=[.!?])\s+', s) if x.strip()]


EM_DASH_SPLIT = re.compile(r"\s+[—–]\s+|\s+&mdash;\s+")
PAYOFF_OPENER = re.compile(r"^(and|but|so|or|yet|then|where|what|why|how|when|which|who)\b", re.I)


def em_dash_punchline(heading):
    """An em-dash heading that withholds and then pays off.

    "Where an agent runs matters — and it changes." sets up a reveal. "Akka SDK
    — removes infrastructure cost" and "Meeting 1 — Dana Whitfield, CISO" use
    the dash the way a colon is used, which is a title. The reveal shows up as a
    conjunction or a wh-word after the dash, or as a full clause on both sides.
    """
    parts = EM_DASH_SPLIT.split(heading)
    if len(parts) != 2:
        return None
    left, right = (p.strip() for p in parts)
    if not (left and right):
        return None
    if PAYOFF_OPENER.match(right):
        return '%s — %s' % (left, right)
    if CLAUSE_VERB.search(left) and CLAUSE_VERB.search(right):
        return '%s — %s' % (left, right)
    return None


def paragraphs(text, path):
    """Yield (line, paragraph) with wrapped source lines joined.

    Antithesis built out of parallel framing spans sentences, and the line-based
    scan in `blocks` sees each wrapped line on its own, so it cannot see the
    shape at all.
    """
    if path.lower().endswith(('.md', '.markdown')):
        buf, start = [], 0
        for i, raw in enumerate(text.split('\n'), 1):
            s = raw.strip()
            item = re.match(r'^(?:[-*]|\d+\.)\s+(.*)$', s)
            # A list item is its own unit. Its wrapped continuation lines are
            # indented, so they fall through and join the buffer it opened.
            if item:
                if buf:
                    yield start, ' '.join(buf)
                buf, start = [re.sub(r'[*_`\[\]]', '', item.group(1))], i
                continue
            # A markdown blockquote holds our own copy as often as a quotation,
            # so its text is audited with the marker stripped.
            quote = re.match(r'^>\s?(.*)$', s)
            if quote:
                s = quote.group(1).strip()
                if not s:
                    if buf:
                        yield start, ' '.join(buf)
                        buf = []
                    continue
            elif not s or s.startswith(('|', '```', '#', '<!--')):
                if buf:
                    yield start, ' '.join(buf)
                    buf = []
                continue
            if not buf:
                start = i
            buf.append(re.sub(r'[*_`\[\]]', '', s))
        if buf:
            yield start, ' '.join(buf)
        return
    for m in re.finditer(r'<p(?:\s[^>]*)?>(.*?)</p>', text, re.S):
        s = re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', '', m.group(1))).strip()
        if len(s) > 3:
            yield text[:m.start()].count('\n') + 1, s


# "On a hyperscaler, …" against "On Akka, …". The framing preposition plus a
# named subject sets up a comparison the next unit completes.
FRAME_OPENER = re.compile(r"^(On|With|Without|Under|Inside|For)\s+([^,]{2,45}),\s+\S")


def parallel_frames(units):
    """Two prose units framed identically with different subjects.

    The contrast carries the claim, which is antithesis whether or not either
    half is negated, so the parallel-negation rule in the bank does not see it.
    Bounded to adjacent units, because two identical openers pages apart are
    coincidence rather than a construction.
    """
    hits, prev = [], None
    for index, (line, para) in enumerate(units):
        for sent in sentences(para):
            m = FRAME_OPENER.match(sent)
            if not m:
                continue
            prep, subject = m.group(1).lower(), m.group(2).strip().lower()
            if prev and prev[0] == prep and prev[1] != subject and index - prev[2] <= 1:
                hits.append((line, '%s %s, … %s %s,'
                             % (m.group(1), prev[1], m.group(1), m.group(2))))
            prev = (prep, subject, index)
    return hits


LEDE_BLOCK = re.compile(r'<p class="[^"]*\blede\b[^"]*"[^>]*>(.*?)</p>', re.S)
LEDE_SUBJECT = re.compile(
    r"^(?:Each|Every|The|A|An|This|These|Our|Akka(?:'s)?)\s+([a-z][\w-]*)", re.I)
COPULA = re.compile(r"\b(is|are|was|were|means?|describes?|covers?|contains?)\b", re.I)


def lede_defines_subject(text):
    """The first sentence of a lede names a thing the reader has not met.

    A lede earns its noun by defining it ("Each guide is a short answer to …")
    or by reusing one the headline already established. Only the page's own
    subject is checked; whether a sentence sounds like speech is not something
    a pattern can decide, so that half of the rule stays with the writer.
    """
    m = LEDE_BLOCK.search(text)
    if not m:
        return None
    lede = re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', '', m.group(1))).strip()
    first = sentences(lede)[0] if sentences(lede) else ''
    subj = LEDE_SUBJECT.match(first)
    if not subj or COPULA.search(first):
        return None
    noun = subj.group(1).rstrip('s')
    h1 = re.search(r'<h1[^>]*>(.*?)</h1>', text, re.S)
    head = re.sub(r'<[^>]+>', '', h1.group(1)).lower() if h1 else ''
    if noun in head:
        return None
    return text[:m.start()].count('\n') + 1, noun, first[:110]


def _exempt(sentence, match):
    """True when the flagged wording sits inside a verifiable factual claim.

    Scoped to the span around the match rather than the whole block, so one
    exempt phrase in a paragraph does not clear an unrelated hit later in it.
    """
    lo, hi = max(0, match.start() - 30), min(len(sentence), match.end() + 30)
    return bool(EXEMPT.search(sentence[lo:hi]))


QUOTED = re.compile(r'"[^"]{4,}"|&ldquo;.{4,}?&rdquo;|“.{4,}?”', re.S)


def in_quote(block, sent):
    """A sentence sitting inside quotation marks is speech, not copy.

    Dialogue in a role-play brief and a customer quote in a case study are both
    evidence of how someone talks, and rewriting either misrepresents them. The
    HTML path already skips a block that opens on a quote; this covers a quote
    that sits inside a longer line.
    """
    at = block.find(sent)
    if at < 0:
        return False
    return any(m.start() < at + len(sent) and at < m.end() for m in QUOTED.finditer(block))


def structural(line, kind, s):
    """Checks that need a whole sentence: fragments, bare subjects, splices."""
    out = []
    for sent in sentences(s):
        if in_quote(s, sent):
            continue
        if FRAGMENT_ANSWER.match(sent):
            rule, match = 'fragment answer', sent
        elif is_fragment(sent):
            rule, match = 'phrase written as a sentence', sent[:110]
        elif bare_subject(sent):
            rule, match = 'sentence cannot stand alone', sent[:110]
        elif punctuated_without_verb(sent):
            rule, match = 'phrase punctuated as a sentence', sent[:110]
        elif ELIDED_PREDICATE.search(sent):
            rule, match = 'elided predicate', sent[:110]
        elif is_comma_splice(sent):
            rule, match = 'comma-spliced clause chain', sent[:110]
        else:
            continue
        out.append({'line': line, 'kind': kind, 'rule': rule,
                    'match': match, 'context': s[:170]})
    for match in unbound_deictic(s):
        out.append({'line': line, 'kind': kind,
                    'rule': 'back-reference to a thing never named',
                    'match': match, 'context': s[:170]})
    return out


# Two kinds of file are not ours to rewrite. A catalogue quotes the banned
# constructions in order to ban them, and rewriting it would delete the
# examples. Verbatim text is a standard document such as a code of conduct,
# where the wording is the point. Both carry the marker in the file, so a reader
# sees the exemption and no filename list is maintained anywhere.
CATALOGUE = re.compile(r"prose-audit:\s*(catalogue|verbatim)", re.I)


def audit_file(path, context=None, standalone_zones=('answer', 'faq')):
    with open(path, encoding='utf-8') as f:
        text = f.read()
    if CATALOGUE.search(text):
        return []
    is_md = path.lower().endswith(('.md', '.markdown'))
    if not is_md:
        text = strip_non_prose(text)
    hits = []
    lede = lede_defines_subject(text)
    if lede:
        hits.append({'line': lede[0], 'kind': 'prose', 'rule': 'lede does not define its subject',
                     'match': '"%s" is not defined or in the headline' % lede[1],
                     'context': lede[2]})
    for line, match in parallel_frames(list(paragraphs(text, path))):
        hits.append({'line': line, 'kind': 'prose', 'rule': 'parallel-frame antithesis',
                     'match': match[:110], 'context': match[:170]})
    for line, kind, s in blocks(text, path):
        if kind == 'quote':
            continue
        for pat, rule, scope, ctxs in RULES:
            # A diagram label is held to the heading rules. An FAQ heading is
            # exempt from them, since a question is the form the section takes.
            k = kind
            if kind == 'diagram' or (kind == 'faq-heading' and scope == 'both'):
                k = 'heading'
            if scope != 'both' and scope != k:
                continue
            if context and context not in ctxs:
                continue
            m = re.search(pat, s, re.I | re.MULTILINE)
            if m and not _exempt(s, m):
                hits.append({'line': line, 'kind': kind, 'rule': rule,
                             'match': m.group(0)[:110], 'context': s[:170]})
        if kind in ('heading', 'diagram'):
            payoff = em_dash_punchline(s)
            if payoff:
                hits.append({'line': line, 'kind': kind, 'rule': 'em-dash-punchline heading',
                             'match': payoff[:110], 'context': s[:170]})
        # A diagram labels its parts. A sentence in one restates the drawing.
        if kind == 'diagram' and s.endswith('.') and len(s.split()) >= 6:
            hits.append({'line': line, 'kind': kind, 'rule': 'sentence inside a diagram',
                         'match': s[:110], 'context': s[:170]})
        if kind == 'prose':
            hits.extend(structural(line, kind, s))
    for line, said in code_comments(text):
        for pat, rule, scope, ctxs in RULES:
            if scope == 'heading':
                continue
            if context and context not in ctxs:
                continue
            m = re.search(pat, said, re.I)
            if m and not _exempt(said, m):
                hits.append({'line': line, 'kind': 'comment', 'rule': rule,
                             'match': m.group(0)[:110], 'context': said[:170]})
        hits.extend(structural(line, 'comment', said))
    # One hit per rule per line keeps the report readable.
    seen, out = set(), []
    for h in hits:
        k = (h['line'], h['rule'])
        if k not in seen:
            seen.add(k)
            out.append(h)
    return out


def check_drift():
    """The rule bank and conventions/prose.md drift apart silently. Compare their sizes."""
    root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
    skill = os.path.join(root, 'conventions', 'prose.md')
    if not os.path.exists(skill):
        print('conventions/prose.md not found at', skill)
        return 1
    with open(skill, encoding='utf-8') as f:
        md = f.read()
    banned = md.split('## Banned constructions', 1)[-1].split('## Required voice', 1)[0]
    named = re.findall(r'^\*\*(.+?)\*\*', banned, re.M)
    print('constructions named in conventions/prose.md : %d' % len(named))
    print('rules in this auditor           : %d' % len(RULES))
    missing = len(named) - len(set(r[1].split(' (')[0] for r in RULES))
    if missing > 0:
        print('\nconventions/prose.md names more constructions than the bank encodes.')
        print('Some are diction rules a regex cannot express. Review:')
        for n in named:
            print('   -', n)
    return 0


def report(path, hits, only_headings=False):
    out = ['\n=== %s ===' % path]
    hits = [h for h in hits if not only_headings or h['kind'] == 'heading']
    if not hits:
        out.append('  ok')
        return '\n'.join(out)
    by = {}
    for h in hits:
        by.setdefault(h['rule'], []).append(h)
    out.append('  %d violation(s) across %d rule(s)' % (len(hits), len(by)))
    for rule in sorted(by):
        out.append('\n  [%s]' % rule)
        for h in by[rule]:
            out.append('    line %-5d %-8s “%s”' % (h['line'], h['kind'], h['match']))
    return '\n'.join(out)


def main():
    p = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    p.add_argument('files', nargs='*')
    p.add_argument('--context', choices=ALL, help='page type; suppresses rules that do not apply to it')
    p.add_argument('--only-headings', action='store_true')
    p.add_argument('--format', choices=('text', 'json'), default='text')
    p.add_argument('--check-drift', action='store_true', help='compare the rule bank against conventions/prose.md')
    a = p.parse_args()

    if a.check_drift:
        return check_drift()
    if not a.files:
        p.error('give at least one file, or use --check-drift')

    results, total = {}, 0
    for path in a.files:
        hits = audit_file(path, context=a.context)
        if a.only_headings:
            hits = [h for h in hits if h['kind'] == 'heading']
        results[path] = hits
        total += len(hits)

    if a.format == 'json':
        print(json.dumps(results, indent=2))
    else:
        for path, hits in results.items():
            print(report(path, hits, only_headings=a.only_headings))
        print('\nTotal: %d violation(s) across %d file(s)' % (total, len(a.files)))
    return 1 if total else 0


if __name__ == '__main__':
    sys.exit(main())
