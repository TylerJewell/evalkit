# Working agreements for this repository

Any assistant or contributor opening this project reads this file first. The rules named
below live in plain files under version control, outside any tool-specific directory, so a
different assistant reads the same files.

## Read before writing code

| File | Contents |
|---|---|
| `CLAUDE.md` | Module boundary, load-bearing rules, working style, build commands. |
| `conventions/prose.md` | Prose rules for documentation, specifications, Javadoc and commit messages. |
| `docs/specs/` | Specifications and design documents, rendered as HTML. |
| `docs/site/evalkit/` | Reference documentation in the doc.akka.io markdown format. |

## Commands

```shell
# Runs the core tests. Needs an Akka SDK built from the feature/governance branch,
# because evalkit-core reads akka.javasdk.ledger and extends
# akka.javasdk.evaluation.Evaluator. Built from the module: Maven reads every
# module POM in the aggregator before it honours -pl, and evalkit-akka inherits
# an SDK parent whose plugins pull more than the core tests need.
mvn -f evalkit-core/pom.xml test

# Adds evalkit-akka and its TestKit. The README's Build section carries the
# procedure for publishing the SDK locally.
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

`evalkit-core` depends on `akka-javasdk` and on nothing else. A type comes from the SDK when
it carries evidence, and a type stays in evalkit when it holds an invariant the SDK does not
enforce. Adding a third dependency requires an argument that convenience does not satisfy.

Every audit needs a test case it is known to catch. A check that passes by finding nothing
looks the same from outside as a check that works.

Every suppression carries a second test that fails when the suppression outlives its cause.
