package io.akka.evalkit.domain;

import java.time.Duration;

/**
 * How much of a campaign runs at once, and how much actually did.
 *
 * <p><b>Workers over a shared queue, not a partition.</b> A scenario's result does not
 * depend on which worker ran it, so a fixed partition buys no reproducibility and costs
 * stragglers: transcripts vary from 2 messages to 82, and an unlucky split leaves one lane
 * running long after the rest are idle. A shared queue self-balances.
 *
 * <p><b>The configured number is not the interesting one.</b> Akka will run thousands of
 * these without noticing; the model provider will not. A report that quotes only the
 * setting cannot distinguish "we asked for 64" from "we sustained 9 because we were being
 * throttled", and those are entirely different facts about a campaign.
 */
public record Lanes(int configured) {

    public Lanes {
        if (configured < 1) throw new IllegalArgumentException("at least one lane");
        if (configured > 512) {
            // Not an Akka limit. A ceiling this high against a rate-limited provider
            // produces a queue of failing calls rather than throughput.
            throw new IllegalArgumentException("more lanes than any provider will serve: " + configured);
        }
    }

    public static Lanes of(int configured) {
        return new Lanes(configured);
    }

    /**
     * What the run actually sustained.
     *
     * @param busy      summed time inside calls, across all workers
     * @param wallClock elapsed time for the campaign
     */
    public Utilisation over(Duration busy, Duration wallClock, int completed) {
        double seconds = wallClock.toNanos() / 1e9;
        double achieved = seconds <= 0 ? 0 : (busy.toNanos() / 1e9) / seconds;
        return new Utilisation(configured, achieved, seconds, completed);
    }

    /**
     * @param achieved mean concurrent calls in flight — busy time over wall clock
     */
    public record Utilisation(int configured, double achieved, double wallClockSeconds,
                              int completed) {

        public double ratio() {
            return configured == 0 ? 0 : achieved / configured;
        }

        public double throughput() {
            return wallClockSeconds <= 0 ? 0 : completed / wallClockSeconds;
        }

        /**
         * Whether the lanes were the constraint.
         *
         * <p>Near-full utilisation means the harness was the bottleneck and raising lanes
         * would help. Low utilisation means something downstream &mdash; a provider's rate
         * limit, the target's capacity &mdash; was, and raising lanes would only deepen a
         * queue. Worth reporting, because the two look identical from the outside and the
         * remedies are opposite.
         */
        public boolean saturated() {
            return ratio() >= 0.85;
        }

        public String summary() {
            return "%.1f of %d lanes (%.0f%%), %.1f/s over %.0fs — %s".formatted(
                achieved, configured, 100 * ratio(), throughput(), wallClockSeconds,
                saturated() ? "lane-bound; more lanes would help"
                            : "bound downstream; more lanes would just queue");
        }
    }
}
