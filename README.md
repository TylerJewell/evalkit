# Akka Evalkit

**Evaluations for conversational AI.**

[![Build](https://github.com/tylerjewell/evalkit/actions/workflows/build.yml/badge.svg)](https://github.com/tylerjewell/evalkit/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)

[Documentation](docs/site/evalkit/) · [Reporting sample](#reporting-sample) ·
[Metrics](#metrics-and-measurements) · [Quickstart](#quickstart) ·
[Works with](#works-with) · [Command line](#proposed-command-line) ·
[Akka Verify](#akka-verify) ·
[Contributing](CONTRIBUTING.md)

Akka Evalkit is a simple-to-use, open-source LLM evaluation framework, for evaluating large-language model systems. It is similar to Pytest and DeepEval but specialized for unit testing LLM apps and agentic systems authored in Java. Evalkit incorporates the latest research to run evals via metrics such as G-Eval, task completion, answer relevancy, and  hallucinations, which uses LLM-as-a-judge and other NLP models that run locally on your machine.

With Evalkit, you can easily evaluate:
* LLM apps end-to-end as black boxes
* Complete agent trajectories across every decision and action
* Individual agent steps such as LLM calls, tool use, retrieval, and sub-agent handoffs

Use these evaluations to determine the optimal models, prompts, and architecture to improve your AI quality, prevent prompt drifting, or even transition from OpenAI to Claude with confidence.

Evalkit runs campaigns using Akka's durable execution engine, in order to support long-lived, long-process evaluations that cover millions of scenarios.  With durable execution campaigns, you can restart the evaluation engine and continue where the campaign left off.

## Reporting sample

```
Refund policy evaluation
--------------------------------------------------------------------------
  run      2026-08-11T09:14Z           system   claims-svc 4.2.0
  rules    refund-desk v3              rubric   scenario-judge v3
  scope    80 requirements, 400 runs
  record   target/evalkit/refund-policy-20260811T0914Z.md
--------------------------------------------------------------------------

1  What the run found
---------------------

  passed every run  #############################              58
  failed every run  ####                                        9
  varied            ##                                          5
  undecided         ##                                          4
  no result         ##                                          4

In this run, each requirement ran 5 times. Varied means the requirement passed
some runs and failed others. Undecided means that a result was in a judge's
middle confidence. No result means the run stopped before there was an answer
to score.

Five runs is not many. A requirement the system only handles 8 times in 10
would still pass all 5 of them about a third of the time. So a requirement
that passed all 5 could really be working anywhere from 55% to 100% of the
time, and five runs cannot tell you where in that range it sits. Twenty runs
would narrow it to 86% and up, fifty runs to 94% and up.

2  What failed
--------------

     refund-14d            expected GenUC-16a.3, found GenUC-17a
     claim-reopen          expected GenUC-22, found GenUC-19
     no-fee-claim          the reply did not state "no extra cost"
     receipt-per-airline   the reply did not state "each airline"
     tool-scope-1          tool-permission v1: scored 0.50, needed 1.00
     step-count-3          step-efficiency v2: scored 0.62, needed 0.75
     interac-offer         scored 2 of 10: the agent named a Canadian service
     cash-country          scored 3 of 10: offered cash outside Canada
     escalation-missing    scored 1 of 10: no escalation path was given

  Every requirement that failed on all 5 runs, and what the scorer said about
  it. A scorer that computes a number reports the number it got and the number
  it needed.

  3 requirements passed within 0.05 of their threshold: refund-window at 0.78
  against 0.75, receipt-count at 0.52 against 0.50, and tone-check at 0.81
  against 0.80.

3  The requirements that gave different answers between runs
------------------------------------------------------------

  There were 5 varied requirements.

     requirement             + passed   - failed   settled by   runs passed
     ---------------------------------------------------------------------
     refund-30d              - + - + +             judge        3 of 5
     escalation-path         + + + - +             judge        4 of 5
     partial-refund-split    - + - - +             judge        2 of 5
     no-receipt-decline      + - + - +             comparison   3 of 5
     duplicate-claim         - - - + -             comparison   1 of 5

  Each mark is one run, in the order they were started.

  A requirement settled by comparison that varies means the system is giving
  different answers to the same question.

4  How quality was measured
---------------------------

             # passed   x failed   ~ varied   ? unsettled

     specification node      ####################xxx~~?    24
     scenario judge          ############x~~????           18
     required wording        ##########xx?                 12
     task completion                                        0
     tool permission         #####x                         6
     tool correctness        ####x~                         6
     argument correctness    ###?                           4
     turn faithfulness       ##?                            3
     citation faithfulness   #                              1
     turn relevancy          ##                             2
     plan quality                                           0
     plan adherence                                         0
     step efficiency         ###x                           4

  Quality measures are specific to the use case being executed. Counts reflect
  the number of requirements a quality measure checked. Varied means the same
  requirement got a different verdict on different runs. Unsettled means the
  judge was undecided or the run produced nothing.

5  How the judge scored
-----------------------

     10  ######################               18
      9  ##############################       25
      8  ######################               18
     ..... passed, 8 and above ................... 61
      7  ########                              7
      6  ######                                5
      5  ######                                5
      4  ####                                  3
     ..... undecided, 4 to 7 ..................... 20
      3  #####                                 4
      2  ####                                  3
      1  ##                                    2
     ..... failed, 3 and below .................... 9

  Models scored 18 requirements from 1 to 10, with 10 being very confident.
  Every run is scored, so each requirement appears 5 times and there are 90
  scores here.

  The judge agrees with a human reviewer 89-91% of the time on clear-cut
  replies and 53% of the time on borderline ones.

6  What this run cannot tell you
--------------------------------

  These runs stopped before the system produced an answer to score.

     never reached the question                    3
     no reply within 45 seconds                    1
     the judge would not score the answer          0

  These requirements were left out of this run.

     booking changes                               14
     seat fees                                     7

  This kit can show that the system answered correctly from a stated starting
  point. It cannot show that a user reaches that point unaided.

7  What it cost
---------------

     the system under test         350,000 in    20,000 out
     the judge                      38,140 in     4,740 out
     total                         388,140 in    24,740 out

  Tokens the system and the judge sent and received across all 400 runs.

     under 5s      ##########################  190
     5 to 15s      #################           125
     15 to 30s     #######                      52
     30 to 45s     ####                         29
     over 45s      #                             4

  How long the system took to answer, over 400 runs. 33 runs came within 15
  seconds of the 45 second timeout and 4 exceeded it, which is counted as no
  reply. Runs were executed 4 at a time, so these times include waiting for a
  free lane.

8  Against the last run
-----------------------

     improved      ##                                6
     regressed     #                                 2
     unchanged     ###########################      64

  Compares this run with the run on 2026-08-04. Both used rubric
  scenario-judge v3 and policy refund-desk v3. A requirement improved when it
  moved up: failed to undecided, or undecided to passed. 8 requirements are
  new since then and have nothing to compare against.
```

## Metrics and measurements

<details open>
<summary><b>Agentic behaviour</b></summary>

| Metric | Scores |
|---|---|
| `ToolPermission` | Whether an agent called only the tools it was allowed to call |
| `ToolCorrectness` | Whether the expected tools were called, by name, arguments or order |
| `ArgumentCorrectness` | The share of tool calls made with arguments that serve the request |
| `TaskCompletion` | How far what came back matches the task the scenario stated |
| `StepEfficiency` | Whether the run reached the task without steps it did not need |
| `PlanQuality` | Whether the plan the agent formed would achieve the task |
| `PlanAdherence` | Whether the agent then did what it had planned |

</details>

<details>
<summary><b>Retrieval and conversation quality</b></summary>

| Metric | Scores |
|---|---|
| `TurnRelevancy` | The share of exchanges whose reply answered what was asked |
| `TurnFaithfulness` | The share of claims in a reply that the retrieved passages support |
| `CitationFaithfulness` | Whether each citation points at a passage supporting the claim beside it |

</details>

<details>
<summary><b>Your own criteria</b></summary>

A rubric is a judge's prompt, versioned so a score stays interpretable months later.
Decision graphs express a rubric as a tree and call a model only where the branch is
genuinely in doubt.

</details>

## Quickstart

```xml
<dependency>
  <groupId>io.akka.evalkit</groupId>
  <artifactId>evalkit-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

A case names the requirement it exercises. `REFUND-004` below is one, and naming it lets
evalkit settle the case without paying for a model.

```java
var dataset = List.of(
    new Scenario("refund-outside-window",
        Optional.of("REFUND-004"),
        Precursor.replay("I want a refund", "order 4417"),
        "It has been 45 days. Can I still return it?",
        "Refuses, and states the 30-day window"));
```

Run the dataset against your service and print the result.

```java
var plan = new CampaignPlan("refund-policy", dataset, Lanes.of(8),
                            Rubric.load("scenario-judge", 3));

var result = CampaignRunner.run(plan, target, router);

System.out.println(result.report().summary());
```

```
1 judged of 1 (1 asserted, 0 scored) — 1 passed, 0 need review, 0 failed;
0 not reached, 0 unscoreable, 0 scorer failures
```

Connecting a service takes one small adapter, which
[the documentation](docs/site/evalkit/index.html.md) covers.

## Where the eval code goes

A campaign costs provider spend and runs for minutes. A source root at `src/eval/java`
compiles under a Maven profile and stays off the build that runs on every commit.

```
<service>/
├── pom.xml               build-helper adds src/eval under the eval profile
├── specs/
│   └── refund-policy/
│       └── spec.md       acceptance criteria, the source of truth for spec nodes
├── src/
│   ├── main/java/…       the service
│   ├── test/java/…       unit and integration tests, run on every commit
│   └── eval/
│       ├── java/com/acme/evalkit/
│       │   ├── dataset/    ScenarioSource implementations, dataset loaders
│       │   ├── runner/     SystemUnderTest adapter, fixtures, lane configuration
│       │   ├── scorer/
│       │   │   ├── deterministic/  node match, exact match, schema, tool-call assertions
│       │   │   ├── heuristic/      similarity, JSON validity, latency and token budgets
│       │   │   └── agentic/        judge agents, panels, calibration
│       │   └── reports/    renderers and baseline comparison
│       └── resources/
│           ├── datasets/*.jsonl
│           ├── rubrics/*.txt      versioned, never edited in place
│           └── baselines/*.json   prior runs, for band movement
└── target/evalkit/       rendered reports and transcripts, gitignored
```

`reports/` holds renderers. A rendered report is build output and lands in `target/`. A
prior run kept so a new run can be compared against it is an input, and it lands in
`resources/baselines/` under version control.

```xml
<profile>
  <id>eval</id>
  <build><plugins>
    <plugin>
      <groupId>org.codehaus.mojo</groupId>
      <artifactId>build-helper-maven-plugin</artifactId>
      <executions><execution>
        <phase>generate-test-sources</phase>
        <goals><goal>add-test-source</goal></goals>
        <configuration><sources><source>src/eval/java</source></sources></configuration>
      </execution></executions>
    </plugin>
  </plugins></build>
</profile>
```

Campaigns run with `mvn -Peval verify`. The root is added as a test source, so the Akka
TestKit and JUnit stay on the classpath without a second dependency block.

An eval source root declares no endpoints and registers no components. The
`domain`/`application`/`api` convention the evalkit library follows would produce four
near-empty packages there, so the eval tree is organised by function.

## Record once, score many times

A campaign pays for the traffic it causes. Record what the runs produced, and every later
campaign scores those files under a new rubric without reaching the service again.

```java
// after a campaign, write down what it recorded
var dataset = FileLedger.open(Path.of("src/eval/resources/datasets"));
result.completed().forEach(completed ->
    completed.recording().ifPresent(recording -> dataset.save(recording.interaction())));

// any time later, score those files instead of a service
var rescored = CampaignRunner.run(plan, new RecordedInteractions(dataset, dataset.fixtures()),
    router);
```

One file per interaction, with the conversation as prose sections and the figures as list
items.

````markdown
# Interaction

- id: refund-outside-window-01
- session: session-42
- latency: PT1.4S

## System

You are a refund assistant.

## User

It has been 45 days. Can I still return it?

## Model call

### Thinking

The order is 45 days old, which is outside the window.

### Tool search_kb

```arguments
{"query":"return window"}
```

```response
30-day return window
```

### Reply

Our return window is 30 days, so this order sits outside it.

### Tokens

- input: 1204
- output: 38
````

An interaction is identified by its `id` field, so renaming a file while tidying a dataset
leaves every evaluation that names the interaction intact. Two files claiming one id are
refused when the dataset opens.

## Works with

**Akka services.** Campaigns run against real agents under the Akka TestKit, and a campaign
too long for a test runner runs as a durable workflow that survives a deploy.

**Services built on anything else.** A service in another language, reachable over a port,
is evaluated through one small adapter.

**An existing build.** A campaign runs as an ordinary JUnit 5 test, so its result reports
through a job that already exists.

**Existing traces.** Campaigns emit OpenTelemetry spans, so a failing case points at the
step that produced it.

## Akka Verify

[Akka Verify](https://akka.io/platform/verify) is continuous evaluation and governance for
agentic AI.

A campaign tells you how a service behaved on the cases someone thought to write. Verify runs
the same evaluations against live traffic, continuously, and governs what an agent may do
while it does it.

A campaign's results import into Verify directly, and the metric and rubric versions carry
across, so a score recorded in CI and a score recorded in production are one measurement.
Verify adds what a campaign cannot. Policies allow, block or report. Guardrails act inline.
Sanitizers redact, mask and reshape. Escalations put a person in the loop.

Every run leaves a tamper-evident evidence record in the form an auditor or regulator
accepts, mapped against the controls a risk survey of 190 regulations identifies for your
industry. Verify governs agents built on Akka and agents built elsewhere, reading a
third-party agent's traces over OpenTelemetry.

## Proposed command line

None of this is built. The shape is recorded here to be argued with first.

`akka init` scaffolds the eval project structure: `src/eval` as a source root, the `eval`
Maven profile that keeps campaigns off the build that runs on every commit, a starter
dataset, and `target/evalkit` for records. Every command below acts on a project that
already has one. evalkit ships inside the SDK, and no separate install stands between a
service and its evaluations.

| Command | What it does |
|---|---|
| `akka eval run` | Runs a campaign through the project's own build |
| `akka eval check` | Refuses a dataset before it costs anything |
| `akka eval report <record>` | Renders the report from a record |
| `akka eval diff <base> <candidate>` | Reports what changed between two runs |
| `akka eval rescore <record>` | Scores recorded interactions under a newer rubric |
| `akka eval list` | Records under `target/evalkit`, newest first |

`run` reaches the service and `rescore` reaches a judge. `check`, `report`, `diff` and
`list` read files.

`run` passes `--repeats`, `--lanes` and `--tag` through to the campaign, and
`--only-failing <record>` runs the requirements that failed or varied in an earlier one.
A varied requirement is the one most worth asking again.

### What a run leaves behind

A record is markdown: a block of what the run was, then one row per requirement, sorted by
id.

```markdown
# Run

- id: refund-policy-20260811T0914Z
- system: claims-svc 4.2.0
- rules: refund-desk v3
- rubric: scenario-judge v3
- repeats: 5

## Requirements

| requirement | measure | verdict | runs | passed |
|---|---|---|---|---|
| escalation-path | scenario judge | varied | +++-+ | 4 of 5 |
| no-fee-claim | required wording | passed | +++++ | 5 of 5 |
| refund-14d | specification node | failed | ----- | 0 of 5 |
```

One requirement to a line keeps the file readable and greppable. `akka eval diff` reads
those rows and prints what moved.

```
Against the run on 2026-08-04
  --------------------------------------------------------------------------
  requirement            was               now               change
  --------------------------------------------------------------------------
  duplicate-claim        varied  1 of 5    failed  0 of 5    worse
  escalation-path        passed  5 of 5    varied  4 of 5    worse
  no-fee-claim           failed  0 of 5    passed  5 of 5    better
  refund-30d             varied  2 of 5    varied  4 of 5    better
  receipt-per-airline    -                 failed  0 of 5    new
  step-count-3           failed  0 of 5    -                 gone
  --------------------------------------------------------------------------

  62 requirements are unchanged. Both runs used rubric scenario-judge v3 and
  policy refund-desk v3.
```

Six rows out of sixty-eight, because a requirement that did the same thing twice has
nothing to report. `refund-30d` holds its verdict and doubles its passes, which a
comparison of verdicts alone drops. Worse sorts first, so a long list never hides a
regression at the bottom. The id, the timestamp and the token counts differ on
every run and stay out of it. Two runs scored under different rubric or policy versions are refused.

### Exit codes

| Code | Means |
|---|---|
| 0 | every requirement passed |
| 100 | a requirement failed or varied |
| 1 | the dataset was refused, or evalkit broke |

A finding about the service and a defect in this kit leave with different codes, so a build
tells them apart.

## Project status

Version 0.1.0-SNAPSHOT, and nothing is published to Maven Central yet. Both modules need
an Akka SDK built by hand from an unreleased branch, which
[CONTRIBUTING.md](CONTRIBUTING.md) covers. `evalkit-core` reads
`akka.javasdk.ledger.InteractionRecord` and extends `akka.javasdk.evaluation.Evaluator`,
so Java 21 alone no longer builds it.

## Roadmap

- [x] Scoring by named requirement, by metric, and by model judgement
- [x] Agentic, retrieval and conversation metrics
- [x] Decision graphs
- [x] Versioned rubrics that return the reason beside the score
- [x] Results that separate a failure from a run that produced nothing
- [x] Refusal before a campaign spends anything
- [x] Durable campaigns on Akka
- [ ] Measure how far the reasoned rubric moves a score against the plain one
- [ ] Record an agent's plan and steps, so the plan metrics score every run
- [ ] Publish to Maven Central

## Documentation

- [Reference documentation](docs/site/evalkit/)
- [Design documents and the DeepEval comparison](docs/specs/)

## Contributing

Issues and pull requests are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) covers the rules
a contributor would not expect, starting with the requirement that every audit ships with
a case it is known to catch.

## Licence

evalkit is licensed under Apache 2.0. See `LICENSE` and `NOTICE`. Parts of this project
are derived from [DeepEval](https://github.com/confident-ai/deepeval) by Confident AI,
also Apache 2.0.
