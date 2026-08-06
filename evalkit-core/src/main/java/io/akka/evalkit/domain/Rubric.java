package io.akka.evalkit.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A judge's prompt, as versioned data.
 *
 * <p><b>Rubrics are data, not classes.</b> Reference versions theirs &mdash; the export
 * carries {@code Empathy v4} beside {@code Brand Persona Alignment v6} &mdash; and that
 * versioning is only useful if an old version can still be loaded and applied. A rubric
 * compiled into a judge cannot be: changing the wording silently redefines every score
 * ever recorded under its name, and there is no way to re-score history on the old one or
 * to explain a movement as "the ruler changed" rather than "the system changed".
 *
 * <p>So the prompt lives in {@code resources/rubrics/} and every {@link Verdict} carries
 * the id and version that produced it.
 */
public record Rubric(String id, int version, String promptTemplate) {

    private static final List<String> PLACEHOLDERS =
        List.of("replay_history", "simulation_history", "system_output", "expected_outcome");

    public Rubric {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("rubric id required");
        if (version < 1) throw new IllegalArgumentException("rubric version starts at 1");
        if (promptTemplate == null || promptTemplate.isBlank()) {
            throw new IllegalArgumentException("rubric prompt required");
        }
        for (String placeholder : PLACEHOLDERS) {
            if (!promptTemplate.contains("{" + placeholder + "}")) {
                // A rubric missing a placeholder still runs and still returns scores. They
                // are just scores for a question with a piece of the conversation left out.
                throw new IllegalArgumentException(
                    "rubric " + id + " v" + version + " omits {" + placeholder + "}");
            }
        }
    }

    /** Loads {@code rubrics/<id>-v<version>.txt} from the classpath. */
    public static Rubric load(String id, int version) {
        String path = "rubrics/" + id + "-v" + version + ".txt";
        try (InputStream in = Rubric.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("no rubric at " + path);
            return new Rubric(id, version, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }

    /** The prompt with this transcript interpolated. */
    public String render(Transcript transcript) {
        return promptTemplate
            .replace("{replay_history}", transcript.replayHistory())
            .replace("{simulation_history}", transcript.simulationHistory())
            .replace("{system_output}", transcript.systemOutput())
            .replace("{expected_outcome}", transcript.expectedOutcome());
    }

    /**
     * Where the prompt stops being instruction and starts being conversation.
     *
     * <p>Akka's {@code Agent} wants a system message and a user message; the rubric was
     * written as one blob. Splitting at the first data block keeps the model's input
     * byte-identical to the original once the two halves are concatenated &mdash; which
     * matters, because the whole reason to hold this prompt verbatim is comparability
     * with scores already recorded under it. {@code RubricTest} pins that equality.
     */
    private int split() {
        int at = promptTemplate.indexOf("<replay_history>");
        return at < 0 ? promptTemplate.length() : at;
    }

    /** The instruction half: everything before the first data block. */
    public String instructions() {
        return promptTemplate.substring(0, split()).strip();
    }

    /** The data half, interpolated: the tagged blocks and whatever follows them. */
    public String data(Transcript transcript) {
        var tail = promptTemplate.substring(split());
        return new Rubric(id, version, promptTemplate).renderFragment(tail, transcript).strip();
    }

    private String renderFragment(String fragment, Transcript transcript) {
        return fragment
            .replace("{replay_history}", transcript.replayHistory())
            .replace("{simulation_history}", transcript.simulationHistory())
            .replace("{system_output}", transcript.systemOutput())
            .replace("{expected_outcome}", transcript.expectedOutcome());
    }

    /** {@code scenario-judge v2}, for reports and for the id stamped on a verdict. */
    public String label() {
        return id + " v" + version;
    }
}
