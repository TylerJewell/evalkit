# evalkit

An evaluation harness for conversational services: put the system in a known
state, say one thing to it, and score what came back — then report what the run
is and is not allowed to claim.

## Why it is in two pieces

```
evalkit-core   scoring, outcomes, reporting, campaign scheduling   no dependencies
evalkit-akka   durable campaigns and a model judge                 Akka SDK
```

Everything that decides *what a run may claim* is ordinary Java and depends on
nothing. A service written in another language, behind an HTTP port, can be
evaluated by implementing one interface. Only durable campaigns and the judge
itself need a runtime.

A harness that demanded a particular SDK before it would score anything would be
a harness for one company's services.

## The interface a system has to satisfy

```java
public interface SystemUnderTest {
    Prepared prepare(Precursor precursor);         // put it in a known state
    Reply    submit(String sessionId, String text); // say the graded turn
    Map<String, String> fixtures();                 // states you can build
    Set<String> emittableNodes();                   // optional: what you can answer with
}
```

`fixtures()` and `emittableNodes()` exist so a campaign can be **refused before it
runs**. A campaign that fails at minute forty for a reason knowable at minute zero
is a campaign that wasted forty minutes. Both checks were added after real runs:
one naming a fixture nobody built, and one whose forty scenarios expected nodes
the target could not produce on any input — which reads as forty product defects
and is one wrong address.

## What a scenario can produce

| Outcome | Meaning |
|---|---|
| `Asserted` | It named a decision, so it was settled by comparison. No model call, no variance, no cost. |
| `Scored` | It reaches a model boundary, so a judge scored it against a versioned rubric. |
| `NotReached` | The precursor never landed, or nothing answered. Says nothing about the system. |
| `Unscoreable` | The judge refused. Absent evidence, never a verdict. |

The last two are reported separately and never folded into a pass rate. A harness
that could not reach half its states would otherwise report a halved score and look
like a product problem.

## What it refuses to do

- **No single number.** The runs behind a campaign are not the same kind of thing,
  and one percentage has to pick a lie to tell about the rest.
- **A deterministic result is a pass or a fail, never undecided.** Comparison has no
  confidence to be borderline about; the report prints the two populations in
  separate columns so that stays checkable.
- **Absent evidence is not a verdict.** A judge that times out, refuses, or returns
  an unreadable score produces `Unscoreable`, not a zero.
- **Unaccounted model calls make a total a floor.** A target that cannot see its own
  usage reports that, rather than presenting zero as a measurement.

## Status

Early. Version `0.1.0-SNAPSHOT`, built and used against one service. The intended
home is a registry rather than a local `mvn install`.

Known gaps, stated rather than hidden:

- **The bundled rubric returns a bare 1–10 score with no reason.** That is a
  deliberate choice for comparability with scores already recorded under it, but it
  means a judged failure tells you it failed and nothing else. A v3 that explains
  itself, run alongside v2, is the fix.
- **Judge agreement is measured, not assumed** — 89–91% with an independent reviewer
  on clear-cut replies, 53% on borderline ones. The middle band is therefore counted
  undecided and never as a pass. Those figures come from one corpus and one model.
- **Token accounting understates any agent whose memory is off**, because usage is
  read from session memory. The `Accounting` type carries an unaccounted count for
  exactly this, and callers should use it.

## Building

```shell
mvn install     # both modules; core needs no runtime at all
```
