package io.akka.evalkit.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Rubric · versioned data, loaded and interpolated")
class RubricTest {

    private static final Transcript TRANSCRIPT = new Transcript(
        "GenUC-17a: cash payment",
        "User: hello\nAgent: hello",
        "User: I want cash\nAgent: Interac e-transfer it is",
        "Interac e-transfer it is",
        "The agent should inform the user that the payment will be made via Interac.");

    @Test
    @DisplayName("the shipped Scenario Judge loads and carries its version")
    void loadsFromClasspath() {
        var rubric = Rubric.load("scenario-judge", 2);

        assertThat(rubric.label()).isEqualTo("scenario-judge v2");
        assertThat(rubric.promptTemplate()).startsWith("You are a helpful judge.");
        assertThat(rubric.promptTemplate()).contains("Output a single value from 1 to 10.");
    }

    @Test
    @DisplayName("every placeholder is filled, and the conversation arrives intact")
    void renders() {
        var rendered = Rubric.load("scenario-judge", 2).render(TRANSCRIPT);

        assertThat(rendered).contains("User: I want cash");
        assertThat(rendered).contains("The agent should inform the user");
        assertThat(rendered).doesNotContain("{simulation_history}");
        assertThat(rendered).doesNotContain("{expected_outcome}");
        assertThat(rendered).doesNotContain("{replay_history}");
        assertThat(rendered).doesNotContain("{system_output}");
    }

    @Test
    @DisplayName("splitting for the Agent API loses nothing the model would have seen")
    void splitIsLossless() {
        // The prompt is held verbatim so scores stay comparable with the ones already
        // recorded under it. Akka wants a system and a user message, so it is split — and
        // this is what makes that split safe rather than a quiet rewording.
        var rubric = Rubric.load("scenario-judge", 2);

        var rejoined = rubric.instructions() + "\n\n" + rubric.data(TRANSCRIPT);
        var whole = rubric.render(TRANSCRIPT);

        assertThat(normalise(rejoined)).isEqualTo(normalise(whole));
        assertThat(rubric.instructions()).startsWith("You are a helpful judge.");
        assertThat(rubric.instructions()).doesNotContain("<replay_history>");
        assertThat(rubric.data(TRANSCRIPT)).contains("Output a single value from 1 to 10.");
    }

    private static String normalise(String s) {
        return s.replaceAll("\\s+", " ").strip();
    }

    @Test
    @DisplayName("a rubric missing a placeholder is refused at construction")
    void incompleteRubric() {
        // It would otherwise run happily and return scores for a question with part of
        // the conversation left out.
        assertThatThrownBy(() -> new Rubric("partial", 1,
            "judge this: {simulation_history} {system_output} {expected_outcome}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("{replay_history}");
    }

    @Test
    @DisplayName("a missing rubric file fails loudly")
    void missingRubric() {
        assertThatThrownBy(() -> Rubric.load("scenario-judge", 99))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("scenario-judge-v99.txt");
    }

    @Test
    @DisplayName("a transcript with nothing graded is refused, not scored")
    void nothingToGrade() {
        // Setup that never landed must not be scored as a wrong answer — that is the
        // distinction between a broken harness and a broken system.
        assertThatThrownBy(() -> new Transcript("s", "User: hi", "", "", "expected"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("neither a simulation nor an output");
    }

    @Test
    @DisplayName("setup is visible on the transcript, because seeded and walked runs differ")
    void setupIsVisible() {
        assertThat(TRANSCRIPT.hasSetup()).isTrue();
        assertThat(new Transcript("s", "", "User: hi\nAgent: hello", "hello", "expected")
            .hasSetup()).isFalse();
    }
}
