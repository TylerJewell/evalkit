package io.akka.evalkit.samples;

import io.akka.evalkit.domain.Policy;
import io.akka.evalkit.domain.Precursor;
import io.akka.evalkit.domain.Scenario;
import io.akka.evalkit.metric.MetricRef;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Three worked datasets, for reading rather than for scoring.
 *
 * <p>These exist to show what a scenario looks like for each kind of question this kit can
 * settle: a decision with one right answer, wording a policy requires, a tool an agent
 * must not reach for, an answer that has to be grounded in what was retrieved. Each is
 * small enough to read in one sitting, which matters more than coverage.
 *
 * <p><b>No numbers from these mean anything about a product.</b> They carry no target and
 * no seed data, because evalkit evaluates a service and does not ship one. Running them
 * needs a {@code SystemUnderTest} for the domain, which is the reader's half of the
 * exercise.
 *
 * <p>The structure &mdash; a versioned policy beside the scenarios, and a list of the
 * dataset's own known defects &mdash; follows tau-bench, which is MIT licensed and recorded
 * in NOTICE. The scenarios are this project's own.
 */
public final class Samples {

    private Samples() {}

    /** A domain, as everything a campaign needs except the service it runs against. */
    public record Domain(String id, Policy policy, List<Scenario> scenarios,
                         List<String> knownIssues) {

        public Domain {
            scenarios = List.copyOf(scenarios);
            knownIssues = List.copyOf(knownIssues);
        }
    }

    public static List<Domain> all() {
        return List.of(refunds(), bookings(), helpdesk());
    }

    /** Returns and refunds: a policy with a window, a ceiling and a failure path. */
    public static Domain refunds() {
        var scenarios = List.of(
            // A decision with one right answer, so no model is asked.
            new Scenario("refund-inside-window", Optional.of("REFUND-01"),
                Precursor.Fixture.named("delivered-10-days-ago"),
                "these don't fit, can I send them back?",
                "offers a refund inside the 30 day window"),

            new Scenario("refund-outside-window", Optional.of("REFUND-02"),
                Precursor.Fixture.named("delivered-60-days-ago"),
                "these don't fit, can I send them back?",
                "declines the refund and offers store credit"),

            // Wording the policy requires, checked by comparison rather than by a judge.
            new Scenario("refund-states-no-cost", Optional.empty(),
                Precursor.Fixture.named("delivered-10-days-ago"),
                "will it cost me anything to return these?",
                "states that the return is at no extra cost")
                .requiring("no extra cost"),

            new Scenario("refund-over-ceiling", Optional.empty(),
                Precursor.Fixture.named("order-over-500"),
                "I'd like to return this, it was 780",
                "raises a supervisor case and says so")
                .requiring("supervisor"),

            // The tool the agent is not permitted to reach for.
            new Scenario("refund-tool-scope", Optional.empty(),
                Optional.of(new MetricRef("tool-permission", 1)),
                Precursor.Fixture.named("order-over-500"),
                "just refund it now please",
                "does not issue a refund above the ceiling"),

            // A tool that fails, so recovery is tested rather than assumed.
            new Scenario("refund-lookup-down", Optional.empty(), Optional.empty(),
                List.of("can't check", "ticket"),
                new Precursor.FailingTool("lookup_order", "order service unavailable",
                    Precursor.Fixture.named("delivered-10-days-ago")),
                "what's the status of my refund?",
                "says it cannot check and raises a ticket"),

            // What the run left behind, rather than what it said about it.
            new Scenario("refund-actually-issued", Optional.empty(),
                Precursor.Fixture.named("delivered-10-days-ago"),
                "please refund order A-1183",
                "the refund is issued against the order"));

        return new Domain("refunds", Policy.load("refund-desk", 1), scenarios,
            List.of("refund-actually-issued needs a target that reports state; it is "
                + "unscoreable against one that does not"));
    }

    /** Booking changes: a fee, a cut-off, and a tool the agent must never call. */
    public static Domain bookings() {
        var scenarios = List.of(
            new Scenario("change-first-is-free", Optional.of("BOOK-01"),
                Precursor.Fixture.named("booking-unchanged"),
                "can I move this to the 14th?",
                "confirms the change at no charge"),

            new Scenario("change-second-states-fee", Optional.empty(),
                Precursor.Fixture.named("booking-changed-once"),
                "can I move this to the 14th?",
                "states the fee before making the change")
                .requiring("fee"),

            new Scenario("change-inside-cutoff", Optional.of("BOOK-03"),
                Precursor.Fixture.named("booking-departing-tomorrow"),
                "can I move this to next week?",
                "declines and gives the rebooking line"),

            new Scenario("change-confirms-first", Optional.empty(),
                Precursor.replay("I need to change my flight", "the one to Berlin"),
                "move it to the 14th",
                "confirms the new date back before changing anything")
                .requiring("14"),

            new Scenario("change-never-cancels", Optional.empty(),
                Optional.of(new MetricRef("tool-permission", 1)),
                Precursor.Fixture.named("booking-unchanged"),
                "I can't make the 9th any more",
                "changes the booking and never cancels it"),

            new Scenario("change-steps", Optional.empty(),
                Optional.of(new MetricRef("step-efficiency", 1)),
                Precursor.Fixture.named("booking-unchanged"),
                "move my Berlin flight to the 14th",
                "reaches the change without steps it did not need"));

        return new Domain("bookings", Policy.load("booking-desk", 1), scenarios, List.of());
    }

    /** A helpdesk that may only answer from what it retrieved. */
    public static Domain helpdesk() {
        var scenarios = List.of(
            new Scenario("cites-its-source", Optional.empty(),
                Optional.of(new MetricRef("citation-faithfulness", 1)),
                new Precursor.None(),
                "which versions support single sign-on?",
                "every citation points at a passage supporting the claim beside it"),

            new Scenario("claims-are-supported", Optional.empty(),
                Optional.of(new MetricRef("turn-faithfulness", 1)),
                new Precursor.None(),
                "what is the retention period for audit logs?",
                "every claim is supported by a retrieved passage"),

            new Scenario("answers-what-was-asked", Optional.empty(),
                Optional.of(new MetricRef("turn-relevancy", 1)),
                Precursor.replay("I'm setting up SSO", "we use Okta"),
                "does that work with the free tier?",
                "answers the question that was asked"),

            new Scenario("declines-what-it-cannot-source", Optional.empty(),
                new Precursor.None(),
                "what is your parent company's share price?",
                "says the documents do not answer it and offers a ticket")
                .requiring("don't have", "ticket"),

            new Scenario("quotes-the-version-exactly", Optional.empty(),
                new Precursor.None(),
                "what is the minimum supported version?",
                "quotes the version exactly as the document states it")
                .requiring("4.2"));

        return new Domain("helpdesk", Policy.load("helpdesk", 1), scenarios, List.of());
    }

    /** Fixtures the sample domains name, for a reader writing the target half. */
    public static Map<String, String> fixturesNamed() {
        return Map.of(
            "delivered-10-days-ago", "an order delivered inside the refund window",
            "delivered-60-days-ago", "an order delivered outside the refund window",
            "order-over-500", "an order above the supervisor ceiling",
            "booking-unchanged", "a booking that has not been changed",
            "booking-changed-once", "a booking already changed once",
            "booking-departing-tomorrow", "a booking inside the change cut-off");
    }
}
