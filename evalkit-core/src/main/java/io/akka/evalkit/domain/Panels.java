package io.akka.evalkit.domain;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static io.akka.evalkit.domain.RequirementResult.Verdict.FAILED;
import static io.akka.evalkit.domain.RequirementResult.Verdict.NO_RESULT;
import static io.akka.evalkit.domain.RequirementResult.Verdict.PASSED;
import static io.akka.evalkit.domain.RequirementResult.Verdict.UNDECIDED;
import static io.akka.evalkit.domain.RequirementResult.Verdict.VARIED;

/**
 * The report, as panels rendered from a {@link RunRecord}.
 *
 * <p>One table cannot hold this. A run produces an outcome per requirement, the measure
 * that settled each one, what the judge scored, what produced nothing, and what it cost,
 * and those are not one shape. Each panel answers one question and says in its own words
 * what its numbers mean.
 *
 * <p>Panels appear only when they have something to say, and are numbered in the order
 * they appear, so a single-run report is not a report with gaps in it.
 *
 * <p><b>Plain ASCII, 80 columns.</b> Reports are piped to files, captured by CI and pasted
 * into tickets, and box drawing and colour survive none of those.
 */
public final class Panels {

    private static final int WIDTH = 80;
    private static final int STRIP = 20;

    private Panels() {}

    public static String render(RunRecord record) {
        var out = new StringBuilder();
        heading(out, record);

        var panels = new ArrayList<Panel>();
        panels.add((b, n) -> whatTheRunFound(b, n, record));
        if (!record.withVerdict(FAILED).isEmpty()) {
            panels.add((b, n) -> whatFailed(b, n, record));
        }
        if (record.repeats() > 1 && !record.withVerdict(VARIED).isEmpty()) {
            panels.add((b, n) -> whatVaried(b, n, record));
        }
        panels.add((b, n) -> howQualityWasMeasured(b, n, record));
        if (!record.judgedScores().isEmpty()) {
            panels.add((b, n) -> howTheJudgeScored(b, n, record));
        }
        panels.add((b, n) -> whatItCannotTellYou(b, n, record));
        panels.add((b, n) -> whatItCost(b, n, record));

        // The number is passed rather than held, so two reports can render at once.
        for (int i = 0; i < panels.size(); i++) panels.get(i).write(out, i + 1);
        return out.toString();
    }

    @FunctionalInterface
    private interface Panel {
        void write(StringBuilder out, int number);
    }

    // ---- panels ----

    private static void heading(StringBuilder out, RunRecord r) {
        var id = r.identity();
        line(out, id.title() + "      run " + id.runReference() + "    system "
            + id.systemVersion());
        var second = new StringBuilder();
        r.policy().ifPresent(p -> second.append("policy ").append(p.label()).append("     "));
        second.append("rubric ").append(r.judge().rubricId()).append(" v")
            .append(r.judge().rubricVersion()).append("    ")
            .append(count(r.requirementCount(), "requirement")).append(", ")
            .append(count(r.runCount(), "run"));
        line(out, second.toString());
        if (r.recordPath() != null && !r.recordPath().isBlank()) {
            line(out, "record  " + r.recordPath());
        }
    }

    private static void whatTheRunFound(StringBuilder out, int number, RunRecord r) {
        title(out, number, "What the run found");
        var counts = r.byVerdict();
        int total = r.requirementCount();
        int repeats = r.repeats();
        row(out, repeats > 1 ? "passed every run" : "passed", counts.get(PASSED), total);
        row(out, repeats > 1 ? "failed every run" : "failed", counts.get(FAILED), total);
        if (repeats > 1) row(out, "varied", counts.get(VARIED), total);
        row(out, "undecided", counts.get(UNDECIDED), total);
        row(out, "no result", counts.get(NO_RESULT), total);
        blank(out);
        para(out, "In this run, each requirement ran "
            + (repeats > 1
                ? repeats + " times. Varied means the requirement passed some runs and "
                  + "failed others. "
                : "once. ")
            + "Undecided means that a result was in a judge's middle confidence. No "
            + "result means the run stopped before there was an answer to score.");
        blank(out);
        para(out, confidence(repeats));
    }

    /**
     * What a clean sweep of this many runs is worth.
     *
     * <p>The bound is the exact one, {@code alpha^(1/n)}, rather than a normal
     * approximation: every run passing is the extreme of the distribution, which is where
     * an approximation is least accurate and always optimistic.
     */
    static String confidence(int repeats) {
        if (repeats <= 1) {
            return "One run cannot tell a requirement the system meets from one it "
                + "happened to meet. Five runs would show a requirement holds at least "
                + floor(5) + "% of the time, twenty runs at least " + floor(20)
                + "%, fifty at least " + floor(50) + "%.";
        }
        // The illustration is only worth printing while a clean sweep is something an
        // unreliable requirement plausibly does. Past that the range says it alone.
        int shown = 8;
        long sweeps = Math.round(Math.pow(shown / 10.0, repeats) * 100);
        String opening = sweeps >= 1
            ? repeats + " runs is not many. A requirement the system only handles " + shown
              + " times in 10 would still pass all " + repeats + " of them about " + sweeps
              + "% of the time. So a"
            : "A";
        return opening + " requirement that passed all " + repeats + " runs could really be "
            + "working anywhere from " + floor(repeats) + "% to 100% of the time, and "
            + repeats + " runs cannot tell you where in that range it sits.";
    }

    /** The lowest true rate that would produce a clean sweep of n runs 5% of the time. */
    static int floor(int runs) {
        return (int) Math.round(Math.pow(0.05, 1.0 / runs) * 100);
    }

    private static void whatFailed(StringBuilder out, int number, RunRecord r) {
        title(out, number, "What failed");
        for (var requirement : r.withVerdict(FAILED)) {
            hanging(out, requirement.id(), requirement.describe());
        }
        blank(out);
        para(out, "Every requirement that failed"
            + (r.repeats() > 1 ? " on all " + r.repeats() + " runs" : "")
            + ", and what the scorer said about it. A scorer that computes a number "
            + "reports the number it got and the number it needed.");
    }

    private static void whatVaried(StringBuilder out, int number, RunRecord r) {
        title(out, number, "The requirements that gave different answers between runs");
        var varied = r.withVerdict(VARIED);
        para(out, "There " + (varied.size() == 1 ? "was 1 varied requirement."
            : "were " + varied.size() + " varied requirements."));
        blank(out);
        line(out, "     " + pad("requirement", 20) + pad("+ passed   - failed", 21)
            + pad("settled by", 22) + right("runs passed", 11));
        line(out, "     " + "-".repeat(74));
        int slice = 1;
        for (var requirement : varied) {
            var strip = strip(requirement);
            slice = strip.slice();
            line(out, "     " + pad(requirement.id(), 20) + pad(strip.marks(), 21)
                + pad(requirement.measure(), 22)
                + right(requirement.passes() + " of " + requirement.runCount(), 11));
        }
        blank(out);
        para(out, slice == 1
            ? "Each mark is one run, in the order they ran."
            : "Each mark covers " + slice + " runs, in the order they ran. It is + when "
              + "more than half of those " + slice + " passed.");
        blank(out);
        para(out, "A requirement settled by comparison that varies means the system is "
            + "giving different answers to the same question.");
    }

    /** A fixed-width run strip, so the column is the same width at 5 runs and at 500. */
    public record Strip(String marks, int slice) {}

    public static Strip strip(RequirementResult requirement) {
        var runs = requirement.runs();
        int slice = Math.max(1, (runs.size() + STRIP - 1) / STRIP);
        var marks = new ArrayList<String>();
        for (int i = 0; i < runs.size(); i += slice) {
            var chunk = runs.subList(i, Math.min(runs.size(), i + slice));
            long passed = chunk.stream().filter(run -> run.outcome().passed()).count();
            marks.add(passed * 2 > chunk.size() ? "+" : "-");
        }
        return new Strip(marks.size() <= 10 ? String.join(" ", marks)
            : String.join("", marks), slice);
    }

    private static void howQualityWasMeasured(StringBuilder out, int number, RunRecord r) {
        title(out, number, "How quality was measured");
        var rows = r.byMeasure();
        int biggest = rows.values().stream()
            .mapToInt(counts -> counts.values().stream().mapToInt(Integer::intValue).sum())
            .max().orElse(1);
        line(out, right("# passed   x failed   ~ varied   ? unsettled", 5 + 24 + 26));
        blank(out);
        for (var entry : rows.entrySet()) {
            var counts = entry.getValue();
            int unsettled = counts.get(UNDECIDED) + counts.get(NO_RESULT);
            String art = cells(counts.get(PASSED), '#', biggest)
                + cells(counts.get(FAILED), 'x', biggest)
                + cells(counts.get(VARIED), '~', biggest)
                + cells(unsettled, '?', biggest);
            int n = counts.values().stream().mapToInt(Integer::intValue).sum();
            line(out, "     " + pad(entry.getKey(), 24) + pad(art, 28) + right(n, 4));
        }
        blank(out);
        para(out, "Quality measures are specific to the use case being executed. Counts "
            + "reflect the number of requirements a quality measure checked. Varied "
            + "means the same requirement got a different verdict on different runs. "
            + "Unsettled means the judge was undecided or the run produced nothing.");
    }

    private static void howTheJudgeScored(StringBuilder out, int number, RunRecord r) {
        title(out, number, "How the judge scored");
        var scores = r.judgedScores();
        var counts = new int[11];
        scores.forEach(s -> counts[s]++);
        int tallest = 1;
        for (int n : counts) tallest = Math.max(tallest, n);
        band(out, counts, tallest, 10, 8, "passed, 8 and above");
        band(out, counts, tallest, 7, 4, "undecided, 4 to 7");
        band(out, counts, tallest, 3, 1, "failed, 3 and below");
        blank(out);
        long judged = r.requirements().stream().filter(q -> q.measure().equals("scenario judge"))
            .count();
        para(out, "Models scored " + count(judged, "requirement") + " from 1 to 10, with "
            + "10 being very confident."
            + (r.repeats() > 1 ? " Every run is scored, so each requirement appears "
                + r.repeats() + " times and there are " + scores.size() + " scores here." : ""));
        blank(out);
        para(out, "The judge agrees with a human reviewer " + r.judge().clearAgreementLow()
            + "-" + r.judge().clearAgreementHigh() + "% of the time on clear-cut replies "
            + "and " + r.judge().borderlineAgreement() + "% of the time on borderline ones.");
    }

    private static void band(StringBuilder out, int[] counts, int tallest,
                             int high, int low, String label) {
        for (int score = high; score >= low; score--) {
            String art = counts[score] == 0 ? ""
                : "#".repeat(Math.max(1, Math.round(counts[score] / (float) tallest * 30)));
            line(out, "     " + right(score, 2) + "  " + pad(art, 36) + right(counts[score], 3));
        }
        int total = 0;
        for (int score = low; score <= high; score++) total += counts[score];
        String figure = Integer.toString(total);
        String lead = "..... " + label + " ";
        line(out, "     " + dots(lead, 48 - figure.length() - 1) + " " + figure);
    }

    private static void whatItCannotTellYou(StringBuilder out, int number, RunRecord r) {
        title(out, number, "What this run cannot tell you");
        para(out, "These runs stopped before the system produced an answer to score.");
        blank(out);
        var causes = r.causesOfNoResult();
        line(out, "     " + pad("never reached the question", 46)
            + causes.getOrDefault(RunOutcome.Cause.SETUP_FAILED, 0));
        line(out, "     " + pad("no reply within " + r.replyTimeoutSeconds() + " seconds", 46)
            + causes.getOrDefault(RunOutcome.Cause.NO_REPLY, 0));
        line(out, "     " + pad("the judge would not score the answer", 46) + r.unscoreable());
        if (!r.coverage().excluded().isEmpty()) {
            blank(out);
            para(out, "These requirements were left out of this run.");
            blank(out);
            for (var journey : r.coverage().excluded()) {
                line(out, "     " + pad(journey.name(), 46) + journey.requirements());
            }
        }
        blank(out);
        para(out, "This kit can show that the system answered correctly from a stated "
            + "starting point. It cannot show that a user reaches that point unaided.");
    }

    private static void whatItCost(StringBuilder out, int number, RunRecord r) {
        title(out, number, "What it cost");
        var spend = r.spend();
        line(out, "     " + pad("the system under test", 30)
            + right(group(spend.system().input()) + " in", 12) + "   "
            + right(group(spend.system().output()) + " out", 11));
        line(out, "     " + pad("the judge", 30)
            + right(group(spend.judge().input()) + " in", 12) + "   "
            + right(group(spend.judge().output()) + " out", 11));
        line(out, "     " + pad("total", 30)
            + right(group(spend.total().input()) + " in", 12) + "   "
            + right(group(spend.total().output()) + " out", 11));
        blank(out);
        para(out, "Tokens the system and the judge sent and received across all "
            + count(r.runCount(), "run") + ".");
        if (spend.basis() == Tokens.Basis.PARTIAL) {
            blank(out);
            para(out, spend.callsMissingUsage() + " model replies carried no usage figure, "
                + "so these totals are a floor rather than a measurement.");
        }
        latency(out, r);
    }

    /**
     * How long the system took, as a distribution against the timeout.
     *
     * <p>A single average would measure the harness as much as the system: runs execute in
     * lanes, so a run's wall clock includes waiting for a free one. The distribution says
     * what a mean cannot, which is how many runs came close to the timeout that would have
     * turned them into no results.
     */
    private static void latency(StringBuilder out, RunRecord r) {
        var latencies = r.latencies();
        blank(out);
        if (latencies.isEmpty()) {
            para(out, "The target reported no timings, so this run cannot say how long "
                + "the system took to answer.");
            return;
        }
        int timeout = r.replyTimeoutSeconds();
        var edges = List.of(timeout / 9, timeout / 3, (timeout * 2) / 3, timeout);
        var labels = new ArrayList<String>();
        var counts = new int[edges.size() + 1];
        for (Duration latency : latencies) {
            long seconds = latency.toMillis() / 1000;
            int bucket = edges.size();
            for (int i = 0; i < edges.size(); i++) {
                if (seconds < edges.get(i)) { bucket = i; break; }
            }
            counts[bucket]++;
        }
        labels.add("under " + edges.get(0) + "s");
        for (int i = 1; i < edges.size(); i++) {
            labels.add(edges.get(i - 1) + " to " + edges.get(i) + "s");
        }
        labels.add("over " + timeout + "s");
        int tallest = 1;
        for (int n : counts) tallest = Math.max(tallest, n);
        for (int i = 0; i < labels.size(); i++) {
            String art = counts[i] == 0 ? ""
                : "#".repeat(Math.max(1, Math.round(counts[i] / (float) tallest * 26)));
            line(out, "     " + pad(labels.get(i), 14) + pad(art, 28) + right(counts[i], 3));
        }
        int near = counts[counts.length - 2];
        blank(out);
        para(out, "How long the system took to answer, over " + count(latencies.size(), "run")
            + ". " + near + " came within " + (timeout - edges.get(edges.size() - 2))
            + " seconds of the " + timeout + " second timeout and "
            + counts[counts.length - 1] + " exceeded it, which is counted as no reply. Runs "
            + "were executed " + r.lanes() + " at a time, so these times include waiting "
            + "for a free lane.");
    }

    // ---- layout ----

    private static void title(StringBuilder out, int number, String text) {
        String heading = number + "  " + text;
        blank(out);
        line(out, heading);
        line(out, "-".repeat(heading.length()));
        blank(out);
    }

    private static void row(StringBuilder out, String label, int n, int total) {
        String art = n == 0 ? "" : "#".repeat(Math.max(1, Math.round(n / (float) total * 40)));
        line(out, "  " + pad(label, 18) + pad(art, 42) + right(n, 3));
    }

    private static String cells(int count, char glyph, int biggest) {
        if (count == 0) return "";
        return String.valueOf(glyph)
            .repeat(Math.max(1, Math.round(count / (float) biggest * 26)));
    }

    private static String dots(String lead, int width) {
        var text = new StringBuilder(lead);
        while (text.length() < width) text.append('.');
        return text.toString();
    }

    private static void hanging(StringBuilder out, String label, String text) {
        int indent = 5 + 22;
        var current = new StringBuilder();
        boolean first = true;
        for (String word : text.split(" ")) {
            if (current.length() + word.length() + 1 > WIDTH - indent - 1) {
                line(out, (first ? "     " + pad(label, 22) : " ".repeat(indent)) + current);
                current.setLength(0);
                first = false;
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) {
            line(out, (first ? "     " + pad(label, 22) : " ".repeat(indent)) + current);
        }
    }

    private static void para(StringBuilder out, String text) {
        var current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (current.length() + word.length() + 1 > WIDTH - 4) {
                line(out, "  " + current);
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) line(out, "  " + current);
    }

    private static void blank(StringBuilder out) {
        out.append('\n');
    }

    private static void line(StringBuilder out, String text) {
        out.append(text.stripTrailing()).append('\n');
    }

    private static String count(long n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    private static String group(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
    }

    private static String right(String text, int width) {
        return text.length() >= width ? text : " ".repeat(width - text.length()) + text;
    }

    private static String right(long value, int width) {
        return right(Long.toString(value), width);
    }
}
