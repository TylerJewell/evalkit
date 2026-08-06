package io.akka.evalkit.application;

import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.akka.evalkit.domain.Band;
import io.akka.evalkit.domain.Rubric;
import io.akka.evalkit.domain.Transcript;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scores recorded Reference transcripts with our judge, and compares.
 *
 * <p>This is the experiment the whole comparison rests on. If our judge and theirs
 * disagree about the same transcripts, then no before/after number either of us produces
 * means anything, and that is worth knowing before any of it is built.
 *
 * <p><b>It measures two things at once and cannot separate them.</b> Reference scored with
 * "GPT 5.5 or equivalent"; this scores with Gemini. Agreement would say the rubric is
 * portable across models *and* that our implementation is faithful. Disagreement cannot
 * tell those apart without a second run on their model. Stated here rather than in the
 * write-up, because it is the first thing a reader should be told.
 *
 * <p>Opt-in, because it costs money and calls a live model:
 *
 * <pre>
 * cd evalkit
 * mvn test -Dtest=JudgeCalibrationTest -Dcalibration=true \
 *          -Dreference.sample=/path/to/sample.jsonl -Dcalibration.lanes=8
 * </pre>
 */
@DisplayName("Judge calibration · our scores against Reference's")
@EnabledIfSystemProperty(named = "calibration", matches = "true")
class JudgeCalibrationTest extends TestKitSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Rubric RUBRIC = Rubric.load("scenario-judge", 2);

    private record Sample(String corpus, String scenario, Transcript transcript, int theirs) {}

    private record Scored(Sample sample, int ours) {
        Band ourBand() {
            return Band.of(ours);
        }

        Band theirBand() {
            return Band.of(sample.theirs());
        }
    }

    @Test
    @DisplayName("score the sample and report agreement")
    void calibrate() throws Exception {
        var samples = load(Path.of(System.getProperty("reference.sample")));
        int lanes = Integer.getInteger("calibration.lanes", 8);
        System.out.printf("judging %d transcripts across %d lanes%n", samples.size(), lanes);

        var scored = new ArrayList<Scored>();
        var failures = new ArrayList<String>();
        var done = new AtomicInteger();
        var busyNanos = new java.util.concurrent.atomic.AtomicLong();

        // The parallelism quotient, in its simplest form. Model rate limits bind long
        // before Akka does, so the useful number is the one that was achieved, not the
        // one configured — reported at the end.
        long startedAt = System.nanoTime();
        try (var pool = Executors.newFixedThreadPool(lanes)) {
            List<Callable<Void>> work = samples.stream().map(sample -> (Callable<Void>) () -> {
                long began = System.nanoTime();
                try {
                    var result = componentClient.forAgent()
                        .inSession("calibration-" + sample.scenario().hashCode())
                        .method(ScenarioJudge::judge)
                        .invoke(new ScenarioJudge.JudgeRequest(sample.transcript(), RUBRIC));
                    synchronized (scored) {
                        scored.add(new Scored(sample, result.score()));
                    }
                } catch (RuntimeException e) {
                    synchronized (failures) {
                        failures.add(sample.scenario() + " — " + rootMessage(e));
                    }
                } finally {
                    busyNanos.addAndGet(System.nanoTime() - began);
                }
                int n = done.incrementAndGet();
                if (n % 50 == 0) System.out.printf("  %d/%d%n", n, samples.size());
                return null;
            }).toList();
            pool.invokeAll(work);
        }
        double elapsed = (System.nanoTime() - startedAt) / 1e9;

        // Busy time over wall clock: the concurrency actually sustained. Configured
        // lanes is an upper bound nobody reaches once the provider starts throttling,
        // and reporting only the setting hides that entirely.
        double achieved = busyNanos.get() / 1e9 / elapsed;
        report(scored, failures, samples.size(), lanes, elapsed, achieved);
    }

    // ---- reporting ----

    private void report(List<Scored> scored, List<String> failures, int total,
                        int lanes, double elapsed, double achieved) throws Exception {
        if (scored.isEmpty()) {
            throw new AssertionError("no transcript scored; failures: " + failures);
        }

        int exact = 0;
        int withinOne = 0;
        int sameBand = 0;
        long absError = 0;
        var matrix = new EnumMap<Band, Map<Band, Integer>>(Band.class);
        var perCorpus = new java.util.TreeMap<String, int[]>();   // [agreed, n]

        for (Scored s : scored) {
            int delta = Math.abs(s.ours() - s.sample().theirs());
            if (delta == 0) exact++;
            if (delta <= 1) withinOne++;
            absError += delta;
            boolean agree = s.ourBand() == s.theirBand();
            if (agree) sameBand++;
            matrix.computeIfAbsent(s.theirBand(), k -> new EnumMap<>(Band.class))
                .merge(s.ourBand(), 1, Integer::sum);
            var c = perCorpus.computeIfAbsent(s.sample().corpus(), k -> new int[2]);
            c[0] += agree ? 1 : 0;
            c[1]++;
        }

        int n = scored.size();
        System.out.println("\n================ judge calibration ================");
        System.out.printf("judged            %d of %d  (%d failed)%n", n, total, failures.size());
        System.out.printf("wall clock        %.1fs at %d lanes → %.1f judgements/s%n",
            elapsed, lanes, n / elapsed);
        System.out.printf("achieved lanes    %.1f of %d configured  (%.0f%% utilisation)%n",
            achieved, lanes, 100.0 * achieved / lanes);
        System.out.printf("%nband agreement    %d/%d  %.1f%%   <- the number that matters%n",
            sameBand, n, 100.0 * sameBand / n);
        System.out.printf("exact score       %d/%d  %.1f%%%n", exact, n, 100.0 * exact / n);
        System.out.printf("within 1 point    %d/%d  %.1f%%%n", withinOne, n, 100.0 * withinOne / n);
        System.out.printf("mean abs error    %.2f points%n", (double) absError / n);

        System.out.println("\nconfusion (rows = Reference, cols = ours)");
        System.out.printf("%-12s%12s%12s%12s%n", "", "NO_MATCH", "PARTIAL", "FAITHFUL");
        for (Band theirs : Band.values()) {
            var row = matrix.getOrDefault(theirs, Map.of());
            System.out.printf("%-12s%12d%12d%12d%n", theirs,
                row.getOrDefault(Band.NO_MATCH, 0),
                row.getOrDefault(Band.PARTIAL, 0),
                row.getOrDefault(Band.FAITHFUL, 0));
        }

        System.out.println("\nband agreement by corpus");
        perCorpus.forEach((corpus, c) ->
            System.out.printf("  %-24s %3d/%3d  %.0f%%%n", corpus, c[0], c[1],
                100.0 * c[0] / c[1]));

        if (!failures.isEmpty()) {
            System.out.println("\nfailures (first 5):");
            failures.stream().limit(5).forEach(f -> System.out.println("  " + f));
        }

        var out = Path.of(System.getProperty("reference.sample")).resolveSibling("calibration.csv");
        var lines = new ArrayList<String>();
        lines.add("corpus,scenario,reference_score,our_score,reference_band,our_band,agree");
        for (Scored s : scored) {
            lines.add("%s,\"%s\",%d,%d,%s,%s,%s".formatted(
                s.sample().corpus(), s.sample().scenario().replace("\"", "'"),
                s.sample().theirs(), s.ours(), s.theirBand(), s.ourBand(),
                s.ourBand() == s.theirBand()));
        }
        Files.write(out, lines);
        System.out.println("\nper-transcript results: " + out);
    }

    // ---- input ----

    private static List<Sample> load(Path path) throws Exception {
        var out = new ArrayList<Sample>();
        for (String line : Files.readAllLines(path)) {
            if (line.isBlank()) continue;
            JsonNode node = MAPPER.readTree(line);
            var transcript = new Transcript(
                node.path("scenario_name").asText(),
                node.path("replay_history").asText(""),
                node.path("simulation_history").asText(""),
                node.path("system_output").asText(""),
                node.path("expected_outcome").asText(""));
            out.add(new Sample(node.path("corpus").asText(),
                node.path("scenario_name").asText(), transcript,
                (int) Math.round(node.path("reference_score").asDouble())));
        }
        return out;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        var msg = cur.getMessage();
        return msg == null ? cur.getClass().getSimpleName()
            : msg.substring(0, Math.min(160, msg.length()));
    }
}
