<!-- <nav> -->
- [Akka](../../index.html)
- [Libraries](../index.html)
- [evalkit](index.html)
- [Testing with the Akka TestKit](testing.html)

<!-- </nav> -->

# Testing with the Akka TestKit

## <a href="about:blank#_overview"></a> Overview

A campaign runs in deterministic mode, judged sample mode, or full corpus mode. The same
scenarios and scorers serve every mode. The mode decides where the model calls come from
and how much a restart costs.

| Mode | Base class | Model | Runs where |
|---|---|---|---|
| Deterministic | `EvalKitSupport` with `TestModelProvider` | Mocked | Every commit. No provider spend. |
| Judged sample | `EvalKitSupport` with a real provider | Real | Before a deploy, on a sample of the corpus. |
| Full corpus | `CampaignWorkflow` in a deployed service | Real | Hours, durable across restarts. |

`EvalKitSupport` extends
[TestKitSupport](../../sdk/agents/testing.html), so a campaign written as a test keeps the
`componentClient`, the `DependencyProvider` wiring, and `TestModelProvider`.

## <a href="about:blank#_where_the_source_root_goes"></a> Where the source root goes

Campaigns cost provider spend and run for minutes. Placing them under `src/test/java`
compiles them on every build and leaves a Surefire exclusion between a pull request and a
provider bill. A separate source root removes that exclusion.

```
src/
├── main/java/…       the service
├── test/java/…       unit and integration tests, run on every commit
└── eval/
    ├── java/…        campaigns, scorers, target adapters
    └── resources/
        ├── datasets/     recorded corpora
        ├── rubrics/      versioned judge prompts
        └── baselines/    prior runs, compared band by band
```

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

Campaigns run with `mvn -Peval verify`. The root is added as a test source so the TestKit
and JUnit stay on the classpath.

## <a href="about:blank#_deterministic_mode"></a> Deterministic mode

`TestModelProvider` replaces an agent's model provider, so a scenario returns the same
answer on every run and costs nothing. Deterministic mode suits the deterministic scorer
family and the tests covering the campaign itself.

[RefundPolicyEval.java](https://github.com/akka/evalkit/blob/main/samples/src/eval/java/RefundPolicyEval.java)
```java
class RefundPolicyEval extends EvalKitSupport { // (1)

  private final TestModelProvider claimsModel = new TestModelProvider();

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withModelProvider(ClaimsAgent.class, claimsModel); // (2)
  }

  @Override
  protected CampaignPlan plan() { // (3)
    return new CampaignPlan("refund-policy", corpus(), Lanes.of(4), Rubric.load("scenario-judge", 2));
  }

  @Eval("an authenticated customer is told the 30-day window") // (4)
  Scenario refundWindowStated() {
    claimsModel.whenMessage(m -> m.contains("fit"))
               .reply("You have 30 days to get a full refund at no extra cost.");

    return Scenario.named("refund-30d")
      .from(Precursor.Fixture.named("authenticated-claim-open"))
      .say("What if these shoes don't fit?")
      .expect("a 30-day full refund at no extra cost");
  }

  @Eval("a delivered bag is not assessed as lost")
  Scenario deliveredBagRoutes() {
    return Scenario.named("GenUC-16a.3")
      .from(Precursor.replay("I lost my bag", "it arrived yesterday"))
      .say("so what happens now?")
      .reaches("GenUC-16a.3"); // (5)
  }
}
```

| **1** | `EvalKitSupport` extends `TestKitSupport`. JUnit 5 supplies the lifecycle and the TestKit supplies the runtime. |
| **2** | The agent under test answers from the mocked provider, so the campaign spends nothing. |
| **3** | The plan names the corpus, the lane count and the rubric. |
| **4** | Each method returns a scenario. The extension collects the class during discovery and runs one planned campaign. |
| **5** | `reaches` names a specification node, so comparison settles this scenario and no judge is called. |

|  | The methods return scenarios and do not assert. A per-method assertion would issue its own model calls at the moment the method runs, which is what makes a suite cost grow with its size and makes a pre-flight refusal impossible. |

## <a href="about:blank#_the_plan_check"></a> The plan check

`EvalKitSupport` runs `CampaignPlan.check` after collecting the class and before the first
call. A campaign naming a fixture the target lacks fails the whole class in under a second
and names the fixture.

```
CampaignPlan refused:
  target cannot build fixtures [authenticated-claim-open]; it knows {ready=a prepared state}
```

Forty scenarios once ran against a conversation that emits a different set of specification
nodes. Every one failed on every fixture and read as forty service defects. The node check
in `CampaignPlan.check` refuses that campaign before it starts.

## <a href="about:blank#_judged_sample_mode"></a> Judged sample mode

Omitting `withModelProvider` sends the campaign to the real provider. The Akka SDK
documentation recommends running evaluators against a real model from integration tests, to
catch regressions before a deploy. Keep the sample small and keep the rubric version fixed,
because a rubric change makes the result incomparable with everything recorded before it.

```java
@Override
protected TestKit.Settings testKitSettings() {
  return TestKit.Settings.DEFAULT; // real provider
}
```

`TelemetryReader` reads the traces and metrics the runtime captured, so token spend and
`EvaluationResult` values can be asserted inside the test.

## <a href="about:blank#_full_corpus_mode"></a> Full corpus mode

A corpus of several hundred scenarios against a live service runs for hours, which no test
runner should hold. `CampaignWorkflow` runs the same plan inside a deployed service, taking
a page of scenarios per durable step.

```java
componentClient.forWorkflow(campaignId)
  .method(CampaignWorkflow::start)
  .invoke(new CampaignWorkflow.Start(campaignId, "scenario-judge", 2, 8, 50)); // (1)
```

| **1** | Lanes set how many scenarios run at once. The wave sets how much a restart costs. |

Workflow state holds counts and a cursor. A restart repeats at most one wave, and resuming
reads a number instead of a corpus.

## <a href="about:blank#_targets_outside_akka"></a> Targets outside Akka

`SystemUnderTest` is the only seam the target crosses, so a service in another language is
evaluated by implementing it. Three capabilities degrade, and each degradation appears in
the report.

| Capability | Akka target | Target outside Akka |
|---|---|---|
| Mocked model | `TestModelProvider` replaces the provider | Unavailable. Deterministic mode needs the target's own stub. |
| Fixture seeding | A typed `ComponentClient` call writes the entity | Needs a fixture endpoint on the target. `Precursor.Replay` walks the conversation instead. |
| Token accounting | Read from telemetry | `spend()` reports a floor and names the calls it could not see. |

`CampaignRunner.run(plan, target, scorer)` in `evalkit-core` takes a scorer as an interface.
A campaign against a service outside Akka therefore runs with no Akka runtime at all.

## <a href="about:blank#_see_also"></a> See also

- [Scoring](scoring.html)
- [Reports](reports.html)
- [Testing the agent](../../sdk/agents/testing.html)
- [Workflows](../../sdk/workflows.html)
