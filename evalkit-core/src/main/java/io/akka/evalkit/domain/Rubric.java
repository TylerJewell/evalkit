package io.akka.evalkit.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A judge's prompt, as versioned data.
 *
 * <p>The prompt lives in {@code resources/rubrics/} and loads by id and version, so an
 * old version can still be applied to stored recordings. A rubric compiled into a judge
 * cannot be. Changing its wording redefines every score already recorded under its name,
 * and the history cannot be re-scored on the wording that produced it.
 *
 * <p>Every {@link Verdict} carries the id and version that produced it.
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

    /**
     * Whether this rubric asks the judge to state why, alongside the score.
     *
     * <p>Read from the prompt rather than configured beside it, for the reason the prompt is
     * data in the first place: the rubric is the only thing that knows what it asked for,
     * and a declaration kept anywhere else can disagree with the text the model was sent.
     *
     * <p>What this decides is how a reply is read. A rubric asking for a bare number is read
     * by {@link Verdict#parseScore}, which takes the first integer in range from anywhere in
     * the reply; a rubric asking for two fields is read by {@link ModelReply}, which requires
     * the label. Choosing the reader by what was asked is what stops a reply that lost its
     * reason from being read as though the reason was never wanted.
     */
    public boolean statesReason() {
        return promptTemplate.contains("SCORE:") && promptTemplate.contains("REASON:");
    }
}
