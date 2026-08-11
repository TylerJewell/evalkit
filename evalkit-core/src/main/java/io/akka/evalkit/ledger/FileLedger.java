package io.akka.evalkit.ledger;

import akka.javasdk.ledger.EvaluationRecord;
import akka.javasdk.ledger.InteractionRecord;
import akka.javasdk.ledger.LedgerClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

/**
 * A {@link LedgerClient} over a directory of markdown interactions.
 *
 * <p>The Akka platform records an interaction to its own storage and serves it through the
 * same interface. This one reads {@code src/eval/resources/datasets}, so a dataset is a set of
 * files a person edits and a reviewer reads in a pull request.
 *
 * <p><b>What this makes possible.</b> An interaction recorded once is scored again whenever a
 * rubric changes, at no provider cost for the traffic. {@link #save} writes what a campaign
 * observed, and a later campaign over {@link RecordedInteractions} scores the same runs under
 * a new rubric.
 *
 * <p>The index is built when the ledger is opened. A file added afterwards is picked up by
 * {@link #reload}.
 *
 * <p>{@link #getEvaluation} finds nothing here. A verdict is written by whatever ran the
 * evaluation, and this ledger holds the interactions rather than the scores.
 */
public final class FileLedger implements LedgerClient {

    /** The extension a dataset entry carries. */
    public static final String EXTENSION = ".md";

    private final Path directory;
    private final Map<String, InteractionRecord> byId = new LinkedHashMap<>();
    private final Map<String, Path> files = new LinkedHashMap<>();

    private FileLedger(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /** Opens the dataset in this directory, reading every markdown file under it. */
    public static FileLedger open(Path directory) {
        var ledger = new FileLedger(directory);
        ledger.reload();
        return ledger;
    }

    /** Reads the directory again, so a file written since {@link #open} is indexed. */
    public void reload() {
        byId.clear();
        files.clear();
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> found = Files.walk(directory)) {
            found.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                .sorted()
                .forEach(this::index);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the dataset at " + directory, e);
        }
    }

    private void index(Path path) {
        String markdown;
        try {
            markdown = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
        var record = InteractionMarkdown.parse(markdown);
        String id = record.interactionId();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException(
                path + " names no interaction id, so nothing can be scored against it");
        }
        Path already = files.get(id);
        if (already != null) {
            // Two entries under one id would let iteration order decide which run a verdict
            // describes, and a report would name a scenario it did not score.
            throw new IllegalStateException(
                "interaction id " + id + " appears in " + already + " and in " + path);
        }
        byId.put(id, record);
        files.put(id, path);
    }

    /** The interaction ids this dataset holds, in the order the files were read. */
    public List<String> ids() {
        return List.copyOf(byId.keySet());
    }

    /** The fixtures a {@link RecordedInteractions} target declares over this dataset. */
    public Map<String, String> fixtures() {
        var out = new LinkedHashMap<String, String>();
        byId.forEach((id, record) -> out.put(id, describe(record)));
        return out;
    }

    private static String describe(InteractionRecord record) {
        String asked = record.inputText().strip();
        String shortened = asked.length() > 60 ? asked.substring(0, 57) + "..." : asked;
        return shortened.isEmpty() ? "a recorded interaction" : "a recorded turn: " + shortened;
    }

    /**
     * Writes an interaction into the dataset and indexes it.
     *
     * <p>The file is named after the id, so a dataset lists in the order a reader would guess.
     * The id inside the file is what identifies it, and renaming the file changes nothing.
     */
    public Path save(InteractionRecord record) {
        String id = record.interactionId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("an interaction needs an id to be saved");
        }
        try {
            Files.createDirectories(directory);
            Path path = directory.resolve(sanitise(id) + EXTENSION);
            Files.writeString(path, InteractionMarkdown.render(record), StandardCharsets.UTF_8);
            byId.put(id, record);
            files.put(id, path);
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write interaction " + id, e);
        }
    }

    /** A file name that survives every filesystem, with the id itself left untouched. */
    private static String sanitise(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    @Override
    public InteractionRecord getInteraction(String interactionId) {
        var record = byId.get(interactionId);
        if (record == null) {
            throw new NoSuchElementException(
                "the dataset at " + directory + " holds no interaction " + interactionId);
        }
        return record;
    }

    @Override
    public CompletionStage<InteractionRecord> getInteractionAsync(String interactionId) {
        return CompletableFuture.completedFuture(getInteraction(interactionId));
    }

    @Override
    public EvaluationRecord getEvaluation(String evaluationId) {
        throw new NoSuchElementException(
            "a file dataset holds interactions and no evaluations: " + evaluationId);
    }

    @Override
    public CompletionStage<EvaluationRecord> getEvaluationAsync(String evaluationId) {
        return CompletableFuture.failedFuture(
            new NoSuchElementException(
                "a file dataset holds interactions and no evaluations: " + evaluationId));
    }
}
