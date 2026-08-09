<!-- <nav> -->
- [Akka](../../index.html)
- [Libraries](../index.html)
- [evalkit](index.html)
- [Testing with the Akka TestKit](testing.html)

<!-- </nav> -->

# Testing with the Akka TestKit

## <a href="about:blank#_overview"></a> Overview

A campaign runs in one of the modes below. The same scenarios and scorers serve every mode.
The mode decides where the model calls come from and how much a restart costs.

| Mode | Runs on | Model | Costs |
|---|---|---|---|
| Deterministic | `TestKitSupport` with `TestModelProvider` | Mocked | Every commit, no provider spend. |
| Opt-in live | `TestKitSupport` with the configured provider | Real | A system property turns it on. |
| Full corpus | `CampaignWorkflow` in a deployed service | Real | Hours, durable across restarts. |

## <a href="about:blank#_deterministic_mode"></a> Deterministic mode

`TestModelProvider` replaces an agent's model provider, so a scenario returns the same answer
on every run and costs nothing. Deterministic mode covers the parsing, the banding and the
campaign machinery, which is where the mistakes that matter live.

[ScenarioJudgeTest.java](https://github.com/tylerjewell/evalkit/blob/main/evalkit-akka/src/test/java/io/akka/evalkit/application/ScenarioJudgeTest.java)
```java
class ScenarioJudgeTest extends TestKitSupport { // (1)

  private final TestModelProvider model = new TestModelProvider();

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withModelProvider(ScenarioJudge.class, model); // (2)
  }

  @Test
  void reasonedRubricCarriesTheReason() {
    model.fixedResponse("SCORE: 9\nREASON: The agent named Interac e-transfer."); // (3)

    var result = judgeUnder(Rubric.load("scenario-judge", 3));

    assertThat(result.score()).isEqualTo(9);
    assertThat(result.reason()).isEqualTo("The agent named Interac e-transfer.");
  }
}
```

| **1** | JUnit 5 supplies the lifecycle and the TestKit supplies the runtime. |
| **2** | The judge answers from the mocked provider, so the test spends nothing. |
| **3** | A harness that can only be tested by calling a model inherits every property that makes model output hard to test. The thing doing the evaluating is held to the standard it enforces. |

## <a href="about:blank#_opt_in_live_mode"></a> Opt-in live mode

A test that calls a real provider costs money on every commit that runs it, so the two here
stay disabled until a system property turns them on.

```shell
# Four transcripts under both rubrics, checking that the path to a provider works.
mvn test -pl evalkit-akka -Dtest=LiveJudgeTest -Dlive=true

# Agreement against a corpus carrying reference scores.
mvn test -pl evalkit-akka -Dtest=JudgeCalibrationTest -Dcalibration=true \
         -Dcalibration.sample=/path/to/sample.jsonl -Dcalibration.lanes=8
```

`LiveJudgeTest` establishes that a rubric reaches a provider and that the reply parses. The
test prints its own caveat, because four transcripts written to have obvious answers say
nothing about how a judge scores a corpus.

`JudgeCalibrationTest` measures agreement against reference scores. Adding
`-Dcalibration.compare=true` scores each transcript under both rubric versions and reports
the band agreement between them, which is what decides whether v3 is a drop-in for v2.

The provider comes from `application.conf` and the key from the environment. The reference
corpus is not in this repository.

## <a href="about:blank#_full_corpus_mode"></a> Full corpus mode

A corpus of several hundred scenarios against a live service runs for hours, which no test
runner should hold. `CampaignWorkflow` runs the same plan inside a deployed service, taking
a page of scenarios per durable step.

[CampaignWorkflow.java](https://github.com/tylerjewell/evalkit/blob/main/evalkit-akka/src/main/java/io/akka/evalkit/application/CampaignWorkflow.java)
```java
componentClient.forWorkflow(campaignId)
  .method(CampaignWorkflow::start)
  .invoke(new CampaignWorkflow.Start(campaignId, "scenario-judge", 3, 8, 50)); // (1)
```

| **1** | The rubric id and version, then the lanes and the wave. Lanes set how many scenarios run at once. The wave sets how much a restart costs. |

Workflow state holds counts and a cursor. A restart repeats at most one wave, and resuming
reads a number instead of a corpus. `CampaignReport` accumulates across waves, so state
carries no transcripts.

A wave that fails twice stops the campaign and keeps the tally, because partial evidence is
still evidence. The report says where it stopped.

## <a href="about:blank#_the_plan_check"></a> The plan check

`CampaignPlan.check` runs before the first call. A campaign naming a fixture the target lacks
fails in under a second and names the fixture.

```
CampaignPlan refused:
  target cannot build fixtures [authenticated-claim-open]; it knows {ready=a prepared state}
```

Forty scenarios once ran against a conversation that emits a different set of specification
nodes. Every one failed on every fixture and read as forty service defects. The node check in
`CampaignPlan.check` refuses that campaign before it starts.

## <a href="about:blank#_where_the_campaigns_go"></a> Where the campaigns go

Campaigns cost provider spend and run for minutes. A separate source root in the consuming
project keeps them off the build that runs on every commit.

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

Campaigns then run with `mvn -Peval verify`. The root is added as a test source so the
TestKit and JUnit stay on the classpath.

## <a href="about:blank#_targets_outside_akka"></a> Targets outside Akka

`SystemUnderTest` is the only seam the target crosses, so a service in another language is
evaluated by implementing it. Three capabilities degrade, and each degradation appears in
the report.

| Capability | Akka target | Target outside Akka |
|---|---|---|
| Mocked model | `TestModelProvider` replaces the provider | Unavailable. Deterministic mode needs the target's own stub. |
| Fixture seeding | A typed `ComponentClient` call writes the entity | Needs a fixture endpoint on the target. `Precursor.replay` walks the conversation instead. |
| Token accounting | Read from the model calls the target reports | `spend()` reports a floor and names the calls it could not see. |

`CampaignRunner.run` in `evalkit-core` takes a `ScorerRouter` or a `Judge`, both of which are
interfaces. A campaign against a service outside Akka runs with no Akka runtime at all.

## <a href="about:blank#_see_also"></a> See also

- [evalkit overview](index.html)
- [Scoring](scoring.html)
- [Testing the agent](../../sdk/agents/testing.html)
- [Workflows](../../sdk/workflows.html)
