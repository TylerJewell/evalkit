package io.akka.evalkit.ledger;

import io.akka.evalkit.domain.InconclusiveScore;
import io.akka.evalkit.metric.ToolCorrectness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading the arguments a recorded tool call carries.
 *
 * <p>{@code ToolCall.arguments()} is a string, and {@link ToolCorrectness} credits a call by
 * the share of keys both sides name with the same value. The parse is what turns one into the
 * other, and a parse that quietly returned nothing would score every call zero.
 */
@DisplayName("Arguments · the parse under a tool call comparison")
class ArgumentsTest {

    @Test
    @DisplayName("members are read as text, whatever JSON type spells them")
    void membersAreReadAsText() {
        assertThat(Arguments.parse("{\"query\":\"refund\",\"limit\":5,\"exact\":true}"))
            .containsExactlyInAnyOrderEntriesOf(
                Map.of("query", "refund", "limit", "5", "exact", "true"));
    }

    @Test
    @DisplayName("a nested object is kept whole rather than flattened")
    void nestedMembersAreKeptWhole() {
        assertThat(Arguments.parse("{\"filter\":{\"country\":\"NL\"}}"))
            .containsEntry("filter", "{\"country\":\"NL\"}");
    }

    @Test
    @DisplayName("what render writes, parse reads back")
    void renderAndParseAgree() {
        var arguments = Map.of("query", "refund", "limit", "5");

        assertThat(Arguments.parse(Arguments.render(arguments))).isEqualTo(arguments);
    }

    @Test
    @DisplayName("a call the target recorded without arguments carries none")
    void anUnrecordedArgumentStringIsNoArguments() {
        assertThat(Arguments.parse("")).isEmpty();
        assertThat(Arguments.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("a string that is not a JSON object reaches no verdict")
    void anUnparseableStringIsNoVerdict() {
        assertThatThrownBy(() -> Arguments.parse("query=refund"))
            .isInstanceOf(InconclusiveScore.class)
            .hasMessageContaining("not readable as JSON");
    }

    @Test
    @DisplayName("a JSON array is not a set of arguments")
    void anArrayIsNotAnObject() {
        assertThat(Arguments.read("[\"refund\"]")).isEmpty();
    }

    /**
     * The case the parse exists for, stated as the metric sees it.
     *
     * <p>Scoring an unreadable argument string zero would report a call made with the wrong
     * arguments, and the failure is in the parse.
     */
    @Test
    @DisplayName("a comparison against an unreadable call declines instead of scoring zero")
    void aComparisonOnUnreadableArgumentsDeclines() {
        var metric = ToolCorrectness
            .expecting(List.of(Interactions.tool("search", Map.of("query", "refund"))))
            .comparingArguments();
        var called = List.of(new akka.javasdk.ledger.ToolCall("", "search", "query=refund", ""));

        assertThatThrownBy(() -> metric.judge(called)).isInstanceOf(InconclusiveScore.class);
    }

    /**
     * The case that proves the comparison is not passing by finding nothing.
     *
     * <p>A parse returning an empty map for every call would score every comparison 1, so a
     * call carrying three of four expected arguments has to score above a call carrying none.
     */
    @Test
    @DisplayName("partial credit tracks the share of keys that agree")
    void partialCreditTracksTheKeysThatAgree() {
        var expected = Map.of("a", "1", "b", "2", "c", "3", "d", "4");
        var metric = ToolCorrectness
            .expecting(List.of(Interactions.tool("search", expected)))
            .comparingArguments();

        double threeOfFour = metric.aggregate(metric.judge(List.of(Interactions.tool(
            "search", Map.of("a", "1", "b", "2", "c", "3", "d", "9")))));
        double noneOfFour = metric.aggregate(metric.judge(List.of(Interactions.tool(
            "search", Map.of("a", "9", "b", "9", "c", "9", "d", "9")))));

        assertThat(threeOfFour).isEqualTo(0.75);
        assertThat(noneOfFour).isEqualTo(0.0);
        assertThat(threeOfFour).isGreaterThan(noneOfFour);
    }
}
