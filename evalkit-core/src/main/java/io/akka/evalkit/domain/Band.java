package io.akka.evalkit.domain;

/**
 * The unit a judge's score can actually be compared in.
 *
 * <p>The Scenario Judge outputs 1&ndash;10, but it is defined in three bands and nothing
 * finer is meaningful. Two judges tuned differently &mdash; or the same judge on a
 * different model, or at a different temperature &mdash; will disagree about 6 versus 7
 * and agree about "somewhat" versus "faithful". Reporting a mean score across campaigns
 * dresses that noise up as signal, so aggregation works in bands and the raw score is
 * kept only for audit.
 */
public enum Band {

    /** 1&ndash;3: the conversation does not match the expected outcome. */
    NO_MATCH(1, 3),

    /** 4&ndash;7: it somewhat matches. */
    PARTIAL(4, 7),

    /** 8&ndash;10: it faithfully matches. */
    FAITHFUL(8, 10);

    private final int low;
    private final int high;

    Band(int low, int high) {
        this.low = low;
        this.high = high;
    }

    public int low() {
        return low;
    }

    public int high() {
        return high;
    }

    public static Band of(int score) {
        for (Band band : values()) {
            if (score >= band.low && score <= band.high) return band;
        }
        throw new IllegalArgumentException("score outside 1-10: " + score);
    }

    /**
     * Whether this band counts as a pass.
     *
     * <p>Only {@link #FAITHFUL} does. {@code PARTIAL} spans 4&ndash;7, which the rubric
     * describes as "somewhat matches" &mdash; treating that as success would report a
     * half-right answer to a customer about money as a passing result.
     */
    public boolean passed() {
        return this == FAITHFUL;
    }

    /**
     * Whether a human has to look at this one.
     *
     * <p>Re-scoring 493 recorded transcripts from the reference dataset on their own
     * rubric agreed with the reference judge 91% of the time on {@code NO_MATCH}, 89% on
     * {@code FAITHFUL}, and <b>53% on {@code PARTIAL}</b>. Agreement at 53% is close to
     * chance, which the rubric's own wording invites: 4&ndash;7 is a four-point range with
     * nothing distinguishing a 4 from a 7.
     *
     * <p>A {@code PARTIAL} is an <em>undecided</em> result. Reporting it as a score lends
     * four points of precision to a grade that does not reproduce, so the report
     * counts how many landed here instead of averaging where they sit.
     */
    public boolean needsReview() {
        return this == PARTIAL;
    }
}
