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
 * Scores recorded reference transcripts with this judge and compares the two scores.
 *
 * <p>Every before-and-after number a campaign prints depends on this agreement. Two
 * judges that disagree about the same transcripts produce two incomparable scales.
 *
 * <p><b>The run measures two things at once and cannot separate them.</b> The reference
 * dataset was scored with "GPT 5.5 or equivalent" and this scores with Gemini, so
 * agreement means the rubric carries across models and this implementation is faithful to
 * it. Disagreement cannot tell those apart without a second run on the reference model.
 *
 * <p>Each line of the sample is a JSON object carrying {@code dataset},
 * {@code scenario_name}, the transcript fields, and {@code reference_score}.
 *
 * <p>Opt-in, because it costs money and calls a live model:
 *
 * <pre>
 * cd evalkit
 * mvn test -Dtest=JudgeCalibrationTest -Dcalibration=true \
 *          -Dcalibration.sample=/path/to/sample.jsonl -Dcalibration.lanes=8
 * </pre>
 */
@DisplayName("Judge calibration · this judge against the reference scores")
@EnabledIfSystemProperty(named = "calibration", matches = "true")
class JudgeCalibrationTest extends TestKitSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Rubric RUBRIC = Rubric.load("scenario-judge", 2);
    private static final Rubric REASONED = Rubric.load("scenario-judge", 3);

    record Sample(String dataset, String scenario, Transcript transcript, int reference) {}

    private record Scored(Sample sample, int ours) {
        Band ourBand() {
            return Band.of(ours);
        }

        Band referenceBand() {
            return Band.of(sample.reference());
        }
    }

    @Test
    @DisplayName("score the sample and report agreement")
    void calibrate() throws Exception {
        var samples = load(Path.of(System.getProperty("calibration.sample")));
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

    /**
     * What asking for a reason did to the score.
     *
     * <p>v3 asks for the same bands in the same words as v2 and adds one sentence. Whether
     * that leaves the score alone is a measurement, not an expectation: a model asked to
     * explain itself may settle somewhere else, and a rubric that moves the bands is a new
     * scale rather than v2 with a reason attached.
     *
     * <p>Costs two judge calls per transcript, so it is opt-in on top of the opt-in above:
     *
     * <pre>
     * mvn test -Dtest=JudgeCalibrationTest -Dcalibration=true -Dcalibration.compare=true \
     *          -Dcalibration.sample=/path/to/sample.jsonl -Dcalibration.lanes=8
     * </pre>
     */
    @Test
    @EnabledIfSystemProperty(named = "calibration.compare", matches = "true")
    @DisplayName("score the sample under v2 and v3 and report what the reason moved")
    void compareRubricVersions() throws Exception {
        var samples = load(Path.of(System.getProperty("calibration.sample")));
        int lanes = Integer.getInteger("calibration.lanes", 8);
        System.out.printf("judging %d transcripts twice across %d lanes%n", samples.size(), lanes);

        var compared = new ArrayList<Compared>();
        var failures = new ArrayList<String>();

        try (var pool = Executors.newFixedThreadPool(lanes)) {
            List<Callable<Void>> work = samples.stream().map(sample -> (Callable<Void>) () -> {
                try {
                    var bare = judge(sample, RUBRIC);
                    var reasoned = judge(sample, REASONED);
                    synchronized (compared) {
                        compared.add(new Compared(sample, bare.score(), reasoned.score(),
                            reasoned.reason()));
                    }
                } catch (RuntimeException e) {
                    synchronized (failures) {
                        failures.add(sample.scenario() + " — " + rootMessage(e));
                    }
                }
                return null;
            }).toList();
            pool.invokeAll(work);
        }
        reportComparison(compared, failures, samples.size());
    }

    private ScenarioJudge.Result judge(Sample sample, Rubric rubric) {
        return componentClient.forAgent()
            .inSession("calibration-v" + rubric.version() + "-" + sample.scenario().hashCode())
            .method(ScenarioJudge::judge)
            .invoke(new ScenarioJudge.JudgeRequest(sample.transcript(), rubric));
    }

    private record Compared(Sample sample, int bare, int reasoned, String reason) {

        Band bareBand() {
            return Band.of(bare);
        }

        Band reasonedBand() {
            return Band.of(reasoned);
        }

        boolean statesReason() {
            return reason != null && !reason.isBlank();
        }
    }

    private void reportComparison(List<Compared> compared, List<String> failures, int total)
        throws Exception {
        if (compared.isEmpty()) {
            throw new AssertionError("no transcript scored under both rubrics; failures: " + failures);
        }

        int n = compared.size();
        int sameBand = 0;
        int sameScore = 0;
        int withReason = 0;
        int bareAgrees = 0;
        int reasonedAgrees = 0;
        long moved = 0;
        var matrix = new EnumMap<Band, Map<Band, Integer>>(Band.class);

        for (Compared c : compared) {
            if (c.bareBand() == c.reasonedBand()) sameBand++;
            if (c.bare() == c.reasoned()) sameScore++;
            if (c.statesReason()) withReason++;
            if (Band.of(c.sample().reference()) == c.bareBand()) bareAgrees++;
            if (Band.of(c.sample().reference()) == c.reasonedBand()) reasonedAgrees++;
            moved += Math.abs(c.reasoned() - c.bare());
            matrix.computeIfAbsent(c.bareBand(), k -> new EnumMap<>(Band.class))
                .merge(c.reasonedBand(), 1, Integer::sum);
        }

        System.out.println("\n=========== scenario-judge v2 against v3 ===========");
        System.out.printf("judged twice      %d of %d  (%d failed)%n", n, total, failures.size());
        System.out.printf("%nband agreement    %d/%d  %.1f%%   <- v3 is a drop-in only if this is high%n",
            sameBand, n, 100.0 * sameBand / n);
        System.out.printf("same score        %d/%d  %.1f%%%n", sameScore, n, 100.0 * sameScore / n);
        System.out.printf("mean movement     %.2f points%n", (double) moved / n);
        System.out.printf("stated a reason   %d/%d  %.1f%%   <- the whole point of v3%n",
            withReason, n, 100.0 * withReason / n);
        System.out.printf("%nagreement with the reference%n");
        System.out.printf("  v2              %d/%d  %.1f%%%n", bareAgrees, n, 100.0 * bareAgrees / n);
        System.out.printf("  v3              %d/%d  %.1f%%%n",
            reasonedAgrees, n, 100.0 * reasonedAgrees / n);

        System.out.println("\nconfusion (rows = v2, cols = v3)");
        System.out.printf("%-12s%12s%12s%12s%n", "", "NO_MATCH", "PARTIAL", "FAITHFUL");
        for (Band bare : Band.values()) {
            var row = matrix.getOrDefault(bare, Map.of());
            System.out.printf("%-12s%12d%12d%12d%n", bare,
                row.getOrDefault(Band.NO_MATCH, 0),
                row.getOrDefault(Band.PARTIAL, 0),
                row.getOrDefault(Band.FAITHFUL, 0));
        }

        if (!failures.isEmpty()) {
            System.out.println("\nfailures (first 5):");
            failures.stream().limit(5).forEach(f -> System.out.println("  " + f));
        }

        var out = Path.of(System.getProperty("calibration.sample"))
            .resolveSibling("calibration-v2-v3.csv");
        var lines = new ArrayList<String>();
        lines.add("dataset,scenario,reference_score,v2_score,v3_score,v2_band,v3_band,same_band,v3_reason");
        for (Compared c : compared) {
            lines.add("%s,\"%s\",%d,%d,%d,%s,%s,%s,\"%s\"".formatted(
                c.sample().dataset(), c.sample().scenario().replace("\"", "'"),
                c.sample().reference(), c.bare(), c.reasoned(),
                c.bareBand(), c.reasonedBand(), c.bareBand() == c.reasonedBand(),
                c.reason().replace("\"", "'").replace("\n", " ")));
        }
        Files.write(out, lines);
        System.out.println("\nper-transcript results: " + out);

        if (withReason == 0) {
            // Not a finding about the judge. Every v3 reply arriving without a reason means
            // the rubric or the reader is broken, and the comparison above measured nothing.
            throw new AssertionError(
                "no v3 judgement carried a reason; the rubric asked for one");
        }
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
        var perDataset = new java.util.TreeMap<String, int[]>();   // [agreed, n]

        for (Scored s : scored) {
            int delta = Math.abs(s.ours() - s.sample().reference());
            if (delta == 0) exact++;
            if (delta <= 1) withinOne++;
            absError += delta;
            boolean agree = s.ourBand() == s.referenceBand();
            if (agree) sameBand++;
            matrix.computeIfAbsent(s.referenceBand(), k -> new EnumMap<>(Band.class))
                .merge(s.ourBand(), 1, Integer::sum);
            var c = perDataset.computeIfAbsent(s.sample().dataset(), k -> new int[2]);
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

        System.out.println("\nconfusion (rows = reference, cols = ours)");
        System.out.printf("%-12s%12s%12s%12s%n", "", "NO_MATCH", "PARTIAL", "FAITHFUL");
        for (Band reference : Band.values()) {
            var row = matrix.getOrDefault(reference, Map.of());
            System.out.printf("%-12s%12d%12d%12d%n", reference,
                row.getOrDefault(Band.NO_MATCH, 0),
                row.getOrDefault(Band.PARTIAL, 0),
                row.getOrDefault(Band.FAITHFUL, 0));
        }

        System.out.println("\nband agreement by dataset");
        perDataset.forEach((dataset, c) ->
            System.out.printf("  %-24s %3d/%3d  %.0f%%%n", dataset, c[0], c[1],
                100.0 * c[0] / c[1]));

        if (!failures.isEmpty()) {
            System.out.println("\nfailures (first 5):");
            failures.stream().limit(5).forEach(f -> System.out.println("  " + f));
        }

        var out = Path.of(System.getProperty("calibration.sample"))
            .resolveSibling("calibration.csv");
        var lines = new ArrayList<String>();
        lines.add("dataset,scenario,reference_score,our_score,reference_band,our_band,agree");
        for (Scored s : scored) {
            lines.add("%s,\"%s\",%d,%d,%s,%s,%s".formatted(
                s.sample().dataset(), s.sample().scenario().replace("\"", "'"),
                s.sample().reference(), s.ours(), s.referenceBand(), s.ourBand(),
                s.ourBand() == s.referenceBand()));
        }
        Files.write(out, lines);
        System.out.println("\nper-transcript results: " + out);
    }

    // ---- input ----

    static List<Sample> load(Path path) throws Exception {
        var out = new ArrayList<Sample>();
        var lines = Files.readAllLines(path);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            JsonNode node = MAPPER.readTree(line);
            var transcript = new Transcript(
                node.path("scenario_name").asText(),
                node.path("replay_history").asText(""),
                node.path("simulation_history").asText(""),
                node.path("system_output").asText(""),
                node.path("expected_outcome").asText(""));
            // A missing reference_score reaches asDouble() as 0, which is a NO_MATCH the
            // reference judge never gave. The agreement figure would move with nothing to
            // show that a field was renamed or dropped.
            JsonNode score = node.get("reference_score");
            if (score == null || !score.isNumber()) {
                throw new IllegalArgumentException(
                    path + " line " + (i + 1) + ": reference_score is missing or not a number");
            }
            out.add(new Sample(node.path("dataset").asText(),
                node.path("scenario_name").asText(), transcript,
                (int) Math.round(score.asDouble())));
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
