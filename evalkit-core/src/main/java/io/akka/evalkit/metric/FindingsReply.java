package io.akka.evalkit.metric;

import io.akka.evalkit.domain.InconclusiveScore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A reply from a model that was asked to judge several things at once.
 *
 * <p>{@link io.akka.evalkit.domain.ModelReply} reads one score and one reason.
 * {@code TurnRelevancy}, {@code TurnFaithfulness} and {@code CitationFaithfulness} each ask
 * about every exchange, claim or citation in a run, so a reply carries a block per subject:
 *
 * <pre>{@code
 * SUBJECT: the claim that the window is 30 days
 * VERDICT: yes
 * REASON: passage 2 states a 30-day window
 *
 * SUBJECT: the claim that shipping is refunded
 * VERDICT: no
 * REASON: no passage mentions shipping
 * }</pre>
 *
 * <p>A reply carrying no block at all produces {@link InconclusiveScore}. An empty finding list
 * means the run had nothing to judge, and a model that answered unreadably is a different
 * fact from a run with nothing in it. Reading one as the other would score a metric 1 on the
 * strength of a reply nobody could parse.
 */
public final class FindingsReply {

    // Asterisks because a model asked for "VERDICT:" returns "**VERDICT:**" often enough to
    // matter, and a run lost to markdown is a run lost to nothing.
    private static final Pattern BLOCK = Pattern.compile(
        "(?ims)^\\s*\\**\\s*SUBJECT\\s*\\**\\s*:\\s*(.+?)\\s*$"
            + ".*?^\\s*\\**\\s*VERDICT\\s*\\**\\s*:\\s*\\**\\s*(\\w+)"
            + "(?:.*?^\\s*\\**\\s*REASON\\s*\\**\\s*:\\s*(.+?)\\s*$)?"
            + "(?=\\s*^\\s*\\**\\s*SUBJECT\\s*\\**\\s*:|\\z)");

    private FindingsReply() {}

    /**
     * The findings the reply states.
     *
     * @throws InconclusiveScore when the reply names no subject, which is a model that did not answer
     */
    public static List<Finding> read(String reply) {
        if (reply == null || reply.isBlank()) {
            throw new InconclusiveScore("the judge returned nothing to read");
        }
        var matcher = BLOCK.matcher(reply);
        var out = new ArrayList<Finding>();
        while (matcher.find()) {
            String subject = strip(matcher.group(1));
            String verdict = matcher.group(2).strip().toLowerCase(Locale.ROOT);
            String reason = matcher.group(3) == null ? "" : strip(matcher.group(3));
            out.add(new Finding(subject, affirmed(verdict), reason));
        }
        if (out.isEmpty()) {
            throw new InconclusiveScore("the judge named no subject: " + abbreviate(reply));
        }
        return List.copyOf(out);
    }

    /**
     * Whether a stated verdict counts as affirmed.
     *
     * <p>Anything other than a plain yes counts against the metric. A model that answered
     * "partially" said the subject does not hold, and reading an unfamiliar word as agreement
     * would raise every score by whatever share of the run the model hedged on.
     */
    private static boolean affirmed(String verdict) {
        return verdict.equals("yes") || verdict.equals("true") || verdict.equals("supported")
            || verdict.equals("relevant");
    }

    private static String strip(String text) {
        return text.replaceAll("\\*+", "").strip();
    }

    private static String abbreviate(String reply) {
        return reply.length() <= 120 ? reply : reply.substring(0, 120) + "…";
    }
}
