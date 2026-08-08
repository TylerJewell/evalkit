package io.akka.evalkit.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The two blocks a run prints: one before it starts, one when it finishes.
 *
 * <p>Written for someone who has to act on the result and does not know how the system is
 * built &mdash; a risk reviewer, an auditor, an engineer on another team. That reader gets
 * one pass through a scrolling log, so the summary states what was tested, what happened,
 * and what to do, in that order.
 *
 * <p><b>Plain ASCII, no colour.</b> Build output is piped to files, captured by CI and
 * pasted into tickets. Box-drawing characters survive none of those reliably, and ANSI
 * colour either disappears or becomes {@code [31m} noise, so severity is carried by
 * position and wording instead.
 *
 * <p><b>The harness supplies structure; the project supplies vocabulary.</b> Journey names,
 * fixture descriptions and finding text all arrive as data. Nothing here knows what a
 * booking reference is.
 */
public final class RunSummary {

    /** Width of the rule, chosen to survive a Maven log prefix in an 80-column terminal. */
    private static final int WIDTH = 72;

    /** Longest bar, in characters. */
    private static final int BAR = 34;

    private RunSummary() {}

    // ---- inputs the harness cannot derive ----

    /** @param systemVersion whatever identifies the build under test, such as a commit */
    public record Identity(String title, String runReference, String systemVersion) {}

    /** @param requirements how many requirements this journey contributes */
    public record Journey(String name, int requirements) {}

    /**
     * What the run covers, and what it does not.
     *
     * <p>{@code excluded} is not decoration. A summary that lists only what was tested
     * invites a reader to assume the rest passed.
     */
    public record Coverage(List<Journey> tested, List<Journey> excluded) {

        public int testedRequirements() {
            return tested.stream().mapToInt(Journey::requirements).sum();
        }

        public int excludedRequirements() {
            return excluded.stream().mapToInt(Journey::requirements).sum();
        }
    }

    /**
     * How far the model doing the judging can be relied on.
     *
     * <p>Measured against an independent reviewer, not asserted. The agreement figures are
     * why the middle band counts as undecided, so they are printed beside the bands rather
     * than in a footnote.
     */
    public record JudgeProfile(String rubricId, int rubricVersion, String unchangedSince,
                               String rubricPath, int clearAgreementLow, int clearAgreementHigh,
                               int borderlineAgreement, String judgedDescription) {}

    /** One thing that did not behave as specified, in a reader's words. */
    public record Finding(String requirement, String whatHappened) {}

    /**
     * What the run is expected to cost, printed before it starts.
     *
     * <p>Stated so a run can be declined on cost before it is paid for. The rates are
     * whatever the caller has measured; they are printed alongside the total so a reader
     * can see the arithmetic rather than trust a single number.
     */
    public record Estimate(long tokensPerConversation, long tokensPerJudgement,
                           int conversations, int judgements) {
        public long total() {
            return tokensPerConversation * conversations + tokensPerJudgement * judgements;
        }
    }

    /**
     * What the run actually cost, printed when it finishes.
     *
     * @param callsMissingUsage replies that carried no usage figure. Non-zero means the
     *                          totals are a floor rather than a measurement.
     */
    public record Spend(Tokens system, Tokens judge, int callsMissingUsage) {
        public Tokens total() {
            return system.plus(judge);
        }

        public Tokens.Basis basis() {
            return callsMissingUsage == 0 ? Tokens.Basis.MEASURED : Tokens.Basis.PARTIAL;
        }
    }

    // ---- the two blocks ----

    /**
     * Printed before the run, so scope is on the record before any result exists.
     *
     * @param fixtureUse how many scenarios start from each named state, and what it
     *                   contains &mdash; descriptions come from the target
     */
    public static String opening(Identity identity, Coverage coverage,
                                 int checkedByRules, int judgedByModel,
                                 JudgeProfile judge, Map<String, String> fixtureDescriptions,
                                 Map<String, Integer> fixtureUse, String scenarioFile,
                                 Estimate estimate) {
        var out = new StringBuilder();
        rule(out);
        line(out, identity.title() + "    run " + identity.runReference()
            + "    system " + identity.systemVersion());
        rule(out);

        int total = coverage.testedRequirements();
        para(out, "This run tests " + total + " requirements: "
            + names(coverage.tested()) + "."
            + (coverage.excluded().isEmpty() ? "" : " It does not test "
                + names(coverage.excluded()) + ", " + coverage.excludedRequirements()
                + " requirements between them."));

        blank(out);
        para(out, checkedByRules + " of the " + total + " are checked against fixed rules "
            + "and cannot vary from one run to the next. The other " + judgedByModel
            + " produce free text for a customer and are judged by a second model.");

        blank(out);
        para(out, judge.judgedDescription() + " The second model reads the exchange and "
            + "scores how closely the reply matches the expected answer:");
        blank(out);
        line(out, "  8-10   matches faithfully          counted as specified");
        line(out, "  4-7    matches in part             counted undecided");
        line(out, "  1-3    does not match              counted as did not");

        blank(out);
        para(out, "It agrees with an independent reviewer "
            + judge.clearAgreementLow() + "-" + judge.clearAgreementHigh()
            + "% of the time on clear-cut replies and " + judge.borderlineAgreement()
            + "% on borderline ones. The middle band is therefore counted undecided, "
            + "never as a pass.");

        blank(out);
        para(out, "Rubric " + judge.rubricId() + " v" + judge.rubricVersion()
            + ", unchanged since " + judge.unchangedSince() + ". Full text in "
            + judge.rubricPath() + ".");

        blank(out);
        para(out, "Each test starts part-way through a conversation rather than at the "
            + "beginning. Before sending its message, the harness puts the system into a "
            + "named starting state, configured for each scenario. This run used "
            + count(fixtureUse.size()) + ":");
        blank(out);
        fixtureUse.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .forEach(e -> line(out, "  " + pad(e.getKey(), 26)
                + pad(fixtureDescriptions.getOrDefault(e.getKey(), ""), 36)
                + right(e.getValue(), 5)));

        blank(out);
        para(out, "Every scenario, and the state it needs, is listed in "
            + scenarioFile + ".");

        if (estimate != null) {
            blank(out);
            para(out, "Expected model usage: about " + round(estimate.total())
                + " tokens. That is " + count(estimate.conversations()) + " conversations at "
                + round(estimate.tokensPerConversation()) + " each and "
                + count(estimate.judgements()) + " judgements at "
                + round(estimate.tokensPerJudgement()) + ", at the rates this kit last "
                + "measured. A scenario that ends early costs less than the estimate, "
                + "never more.");
        }

        blank(out);
        para(out, "This evaluation kit can show that the system answered correctly from a "
            + "stated starting point, but it doesn't prove that a user can reach that "
            + "point unaided.");
        rule(out);
        return out.toString();
    }

    /** Printed when the run finishes. */
    public static String results(CampaignReport report, Coverage coverage,
                                 List<Finding> findings, int replyTimeoutSeconds,
                                 String findingsFile, Spend spend) {
        var out = new StringBuilder();
        int total = report.total();
        rule(out);
        para(out, report.passed() + " of " + total + " requirements behaved as specified, "
            + report.failed() + " did not, " + report.review()
            + " were too borderline to call and " + report.notReachedOrUnscoreable()
            + " produced no result.");
        blank(out);

        // Split by how each result was decided, not merely how it came out. Comparison
        // against a named decision cannot produce an undecided, and neither can a number
        // against a threshold, so a figure in either column's undecided row would be a
        // defect in this kit rather than a finding about the system -- which is only
        // checkable if the three are printed apart.
        line(out, "  " + pad("", 27) + rightText("checked by rules", 17)
            + rightText("measured", 9) + rightText("judged", 9) + rightText("total", 7));
        split(out, "as specified", report.assertedPassed(), report.measuredPassed(),
            report.scoredPassed());
        split(out, "did not", report.assertedFailed(), report.measuredFailed(),
            report.scoredFailed());
        line(out, "  " + pad("undecided", 27) + rightText("-", 17) + rightText("-", 9)
            + right(report.review(), 9) + right(report.review(), 7));
        line(out, "  " + pad("no result", 27) + rightText("", 17) + rightText("", 9)
            + rightText("", 9) + right(report.notReachedOrUnscoreable(), 7));
        sub(out, "never reached the question", report.setupFailed(), total);
        sub(out, "no reply within " + replyTimeoutSeconds + " seconds", report.noReply(), total);
        sub(out, "answer not assessed", report.unscoreable(), total);

        blank(out);
        para(out, explainNonResults(report, replyTimeoutSeconds));

        if (spend != null) {
            blank(out);
            para(out, "Model usage: " + group(spend.total().total()) + " tokens, "
                + group(spend.total().input()) + " in and "
                + group(spend.total().output()) + " out.");
            blank(out);
            line(out, "  " + pad("the system under test", 27)
                + pad(group(spend.system().input()) + " in", 16)
                + group(spend.system().output()) + " out");
            line(out, "  " + pad("the judge", 27)
                + pad(group(spend.judge().input()) + " in", 16)
                + group(spend.judge().output()) + " out");
            if (spend.basis() == Tokens.Basis.PARTIAL) {
                blank(out);
                para(out, count(spend.callsMissingUsage())
                    + " model replies carried no usage figure, so these totals are a "
                    + "floor rather than a measurement.");
            }
        }

        if (report.notReachedOrUnscoreable() > 0) {
            blank(out);
            para(out, "None of the " + report.notReachedOrUnscoreable()
                + " tell us anything about the product. This run therefore reports no "
                + "overall pass rate. The " + report.failed() + " findings below stand.");
        }

        if (!findings.isEmpty()) {
            blank(out);
            findings.stream().limit(3).forEach(f -> hanging(out, f.requirement(), f.whatHappened()));
            int remaining = report.failed() - Math.min(3, findings.size());
            if (remaining > 0) {
                line(out, " ".repeat(12) + remaining + " more in " + findingsFile);
            }
        }

        if (!coverage.excluded().isEmpty()) {
            blank(out);
            para(out, capitalise(names(coverage.excluded())) + " remain untested.");
        }
        rule(out);
        return out.toString();
    }

    // ---- prose the counts imply ----

    private static String explainNonResults(CampaignReport report, int timeout) {
        var parts = new ArrayList<String>();
        if (report.setupFailed() > 0) {
            parts.add("The " + report.setupFailed() + " stopped before the system was asked "
                + "anything: each test must first put it into the situation being examined, "
                + "and that preparation failed.");
        }
        if (report.noReply() > 0) {
            parts.add("The " + report.noReply() + " were asked and did not answer within "
                + timeout + " seconds.");
        }
        if (report.unscoreable() > 0) {
            parts.add("The " + report.unscoreable() + " were answered, but the second model "
                + "would not judge the reply.");
        }
        return parts.isEmpty() ? "Every test produced a result." : String.join(" ", parts);
    }

    // ---- layout ----

    /**
     * A count above zero always draws at least one block.
     *
     * <p>Twelve findings in five hundred rounds to nothing at this width. A row that
     * disappears because it is small is the failure this summary exists to prevent.
     */
    static int blocks(int count, int total) {
        if (count <= 0 || total <= 0) return 0;
        return Math.max(1, (int) Math.round((double) count / total * BAR));
    }

    /** One row of the decision-method table, with its own total. */
    /**
      * One row across the three families, with their total.
      *
      * <p>A campaign that ran no metrics prints a zero in the measured column rather than
      * dropping it, so the column a reader looks for is in the same place on every report.
      */
    private static void split(StringBuilder out, String label,
                              int byRules, int measured, int byModel) {
        line(out, "  " + pad(label, 27) + right(byRules, 17) + right(measured, 9)
            + right(byModel, 9) + right(byRules + measured + byModel, 7));
    }

    private static void bar(StringBuilder out, String label, int count, int total) {
        line(out, "  " + pad(label, 27) + pad("#".repeat(blocks(count, total)), BAR)
            + right(count, 5));
    }

    private static void sub(StringBuilder out, String label, int count, int total) {
        line(out, "    " + pad(label, 29) + pad("#".repeat(blocks(count, total)), BAR - 4)
            + right(count, 5));
    }

    private static void rule(StringBuilder out) {
        out.append("-".repeat(WIDTH)).append('\n');
    }

    private static void blank(StringBuilder out) {
        out.append('\n');
    }

    private static void line(StringBuilder out, String text) {
        out.append(' ').append(text).append('\n');
    }

    /**
     * A finding: its requirement in the left column, its description wrapped under itself.
     *
     * <p>Hanging rather than truncated. A finding cut off mid-sentence is one a reader has
     * to go and look up, which defeats printing it here at all.
     */
    private static void hanging(StringBuilder out, String label, String text) {
        int indent = 12;
        var current = new StringBuilder();
        boolean first = true;
        for (String word : text.split(" ")) {
            if (current.length() + word.length() + 1 > WIDTH - indent - 1) {
                line(out, (first ? " " + pad(label, 11) : " ".repeat(indent)) + current);
                current.setLength(0);
                first = false;
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) {
            line(out, (first ? " " + pad(label, 11) : " ".repeat(indent)) + current);
        }
    }

    /** Wraps at the rule width, so no line runs past the frame. */
    private static void para(StringBuilder out, String text) {
        var current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (current.length() + word.length() + 1 > WIDTH - 2) {
                line(out, current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) line(out, current.toString());
    }

    private static String names(List<Journey> journeys) {
        var list = journeys.stream().map(Journey::name).toList();
        if (list.isEmpty()) return "nothing";
        if (list.size() == 1) return list.get(0);
        return String.join(", ", list.subList(0, list.size() - 1))
            + " and " + list.get(list.size() - 1);
    }

    private static String count(int n) {
        return switch (n) {
            case 1 -> "one";
            case 2 -> "two";
            case 3 -> "three";
            case 4 -> "four";
            case 5 -> "five";
            default -> String.valueOf(n);
        };
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String pad(String text, int width) {
        return text.length() >= width ? text + " " : text + " ".repeat(width - text.length());
    }

    /** Rounded to two significant figures, because an estimate that reads exact isn't. */
    private static String round(long value) {
        if (value < 1000) return Long.toString(value);
        long unit = (long) Math.pow(10, Math.max(0, Long.toString(value).length() - 2));
        return group(Math.round((double) value / unit) * unit);
    }

    /** Thousands separated, so six-figure token counts can be read at a glance. */
    private static String group(long value) {
        return String.format("%,d", value);
    }

    private static String rightText(String text, int width) {
        return " ".repeat(Math.max(0, width - text.length())) + text;
    }

    private static String right(int value, int width) {
        String s = String.valueOf(value);
        return s.length() >= width ? s : " ".repeat(width - s.length()) + s;
    }
}
