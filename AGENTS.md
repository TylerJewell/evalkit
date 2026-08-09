# Working agreements for this repository

Any assistant or contributor opening this project reads this file first. The rules named
below live in plain files under version control, outside any tool-specific directory, so a
different assistant reads the same files.

## Read before writing code

| File | Contents |
|---|---|
| `CLAUDE.md` | Module boundary, load-bearing rules, working style, build commands. |
| `docs/design-history.md` | The incident behind each rule. Read before changing anything the rules cover. |
| `conventions/prose.md` | Prose rules for documentation, specifications, Javadoc and commit messages. |
| `docs/specs/` | Specifications and design documents, rendered as HTML. |
| `docs/site/evalkit/` | Reference documentation in the doc.akka.io markdown format. |

## Commands

```shell
# Runs the core tests, which prove evalkit-core compiles with no Akka present.
mvn -pl evalkit-core test

# Adds evalkit-akka. Needs an Akka SDK built from the feature/governance branch,
# because the evaluation and ledger APIs are not released yet. The README's Build
# section has the five steps. Continuous integration cannot run this.
mvn install

# Audit prose. Exit code 1 on any hit. Never pass conventions/prose.md itself:
# the rules file quotes every construction it bans, so it reports 75 hits.
python tools/audit-prose.py docs/specs/*.html docs/site/evalkit/*.html.md

# Compare the auditor's rule bank against the rules file.
python tools/audit-prose.py --check-drift
```

## Rules an assistant breaks most often

Documentation states what a thing is and how it works. Rationale appears in a limited part
of `README.md` and nowhere else. `conventions/prose.md` carries the full list, and
`tools/audit-prose.py` enforces the part a regex can reach.

Every sentence carries its own subject and its own verb phrase. A sentence closing on a
bare auxiliary borrows its predicate from the sentence above and fails the rule.

A comment describes what is true now. Chronology belongs in a commit message.

## Rules the build enforces

`evalkit-core` declares no dependencies. Adding one requires an argument that convenience
does not satisfy. After touching `evalkit-core`, run `mvn -pl evalkit-core test` and confirm
it compiles with no Akka on the classpath.

Every audit needs a test case it is known to catch. A check that passes by finding nothing
looks the same from outside as a check that works.

Every suppression carries a second test that fails when the suppression outlives its cause.
