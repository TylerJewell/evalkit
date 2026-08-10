package io.akka.evalkit.ledger;

import io.akka.evalkit.application.CampaignRunner;
import io.akka.evalkit.domain.CampaignPlan;
import io.akka.evalkit.domain.Lanes;
import io.akka.evalkit.domain.Precursor;
import io.akka.evalkit.domain.RunOutcome;
import io.akka.evalkit.domain.Rubric;
import io.akka.evalkit.domain.Scenario;
import io.akka.evalkit.domain.ScorerRouter;
import io.akka.evalkit.domain.SystemUnderTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A corpus on disk, recorded once and scored again.
 *
 * <p>The run that produces a corpus costs provider spend. Every run after it reads the files
 * and costs nothing, which is what lets a rubric change be measured against traffic that has
 * already happened.
 */
@DisplayName("FileLedger · a corpus of recorded interactions on disk")
class FileLedgerTest {

    /** A service that answers once per call, counting how often it was reached. */
    private static final class CountingService implements SystemUnderTest {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Map<String, String> fixtures() {
            return Map.of("signed-in", "a signed-in customer");
        }

        @Override
        public Prepared prepare(Precursor precursor) {
            return new Prepared.Ready("session-1", "");
        }

        @Override
        public Reply submit(String sessionId, String userText) {
            calls.incrementAndGet();
            return new Reply("the refund takes 30 days", Optional.of("REFUND-004"),
                Optional.empty(), List.of(Interactions.tool("search_kb")), List.of(), "", Optional.empty());
        }
    }

    private static CampaignPlan plan() {
        return new CampaignPlan("refund-policy",
            List.of(new Scenario("refund-timing", Optional.of("REFUND-004"),
                Precursor.Fixture.named("signed-in"),
                "when do I get my refund?", "States the 30-day window")),
            Lanes.of(1), Rubric.load("scenario-judge", 3));
    }

    @Test
    @DisplayName("a campaign's interactions are saved and read back without the service")
    void aCorpusIsRecordedOnceAndScoredAgain(@TempDir Path corpusDirectory) {
        var service = new CountingService();
        var first = CampaignRunner.run(plan(), service,
            ScorerRouter.judgingEverything(recording -> new RunOutcome.Unscoreable("not judged")));

        var corpus = FileLedger.open(corpusDirectory);
        first.completed().forEach(completed ->
            completed.recording().ifPresent(recording -> corpus.save(recording.interaction())));

        assertThat(service.calls.get()).isEqualTo(1);
        assertThat(corpus.ids()).containsExactly("refund-timing");

        // The second campaign reads the files. The service is never reached again.
        var recorded = new RecordedInteractions(FileLedger.open(corpusDirectory),
            corpus.fixtures());
        var replayed = CampaignRunner.run(
            new CampaignPlan("refund-policy-rescored",
                List.of(new Scenario("refund-timing", Optional.empty(),
                    Precursor.Fixture.named("refund-timing"),
                    "when do I get my refund?", "States the 30-day window")),
                Lanes.of(1), Rubric.load("scenario-judge", 3)),
            recorded,
            ScorerRouter.judgingEverything(recording ->
                new RunOutcome.Measured("rescored", 1, 1.0, 0.5, true)));

        assertThat(service.calls.get()).isEqualTo(1);
        assertThat(replayed.outcomes()).hasSize(1);
        assertThat(replayed.outcomes().get(0).passed()).isTrue();
    }

    @Test
    @DisplayName("a saved interaction keeps its tools and its answer")
    void aSavedInteractionKeepsWhatTheRunDid(@TempDir Path corpusDirectory) {
        var result = CampaignRunner.run(plan(), new CountingService(),
            ScorerRouter.judgingEverything(recording -> new RunOutcome.Unscoreable("not judged")));
        var corpus = FileLedger.open(corpusDirectory);
        result.completed().forEach(completed ->
            completed.recording().ifPresent(recording -> corpus.save(recording.interaction())));

        var reopened = FileLedger.open(corpusDirectory);
        var record = reopened.getInteraction("refund-timing");

        assertThat(record.finalResponseText()).isEqualTo("the refund takes 30 days");
        assertThat(record.toolCalls()).extracting(akka.javasdk.ledger.ToolCall::name)
            .containsExactly("search_kb");
        assertThat(record.inputText()).isEqualTo("when do I get my refund?");
    }

    /**
     * The case the index is known to catch.
     *
     * <p>Two entries under one id would let the order the files were read decide which run a
     * verdict describes, which is the pairing hazard this repository has recorded twice.
     */
    @Test
    @DisplayName("two files claiming one interaction id are refused")
    void aDuplicateIdIsRefused(@TempDir Path corpusDirectory) throws Exception {
        var built = Interactions.identified(
            Interactions.of("session-1", "", "hello", List.of(), Optional.empty(), Optional.empty()),
            "refund-timing");
        Files.writeString(corpusDirectory.resolve("one.md"), InteractionMarkdown.render(built));
        Files.writeString(corpusDirectory.resolve("two.md"), InteractionMarkdown.render(built));

        assertThatThrownBy(() -> FileLedger.open(corpusDirectory))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("refund-timing");
    }

    @Test
    @DisplayName("an entry naming no interaction id is refused")
    void anEntryWithNoIdIsRefused(@TempDir Path corpusDirectory) throws Exception {
        Files.writeString(corpusDirectory.resolve("nameless.md"),
            "# Interaction\n\n## User\n\nhello\n");

        assertThatThrownBy(() -> FileLedger.open(corpusDirectory))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("names no interaction id");
    }

    @Test
    @DisplayName("renaming the file does not rename the interaction")
    void theIdSurvivesARename(@TempDir Path corpusDirectory) throws Exception {
        var built = Interactions.identified(
            Interactions.of("session-1", "", "hello", List.of(), Optional.empty(), Optional.empty()),
            "refund-timing");
        var corpus = FileLedger.open(corpusDirectory);
        Path written = corpus.save(built);
        Files.move(written, corpusDirectory.resolve("renamed-by-a-tidy-up.md"));

        var reopened = FileLedger.open(corpusDirectory);

        assertThat(reopened.ids()).containsExactly("refund-timing");
        assertThat(reopened.getInteraction("refund-timing").inputText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("an interaction the corpus does not hold is named in the failure")
    void anAbsentInteractionIsNamed(@TempDir Path corpusDirectory) {
        var corpus = FileLedger.open(corpusDirectory);

        assertThatThrownBy(() -> corpus.getInteraction("nothing-here"))
            .isInstanceOf(java.util.NoSuchElementException.class)
            .hasMessageContaining("nothing-here");
    }
}
