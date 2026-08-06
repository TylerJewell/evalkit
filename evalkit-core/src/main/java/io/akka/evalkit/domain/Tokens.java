package io.akka.evalkit.domain;

/**
 * Tokens sent to and returned by a model.
 *
 * <p>Kept as two numbers rather than one because they are priced differently by every
 * provider, and a run that is cheap on output can still be expensive on input &mdash;
 * which is the usual shape here, since a judge reads a whole transcript to emit a score
 * and one sentence.
 *
 * @param input tokens in the prompt, including any transcript or rubric it carried
 * @param output tokens the model generated
 */
public record Tokens(long input, long output) {

    public static final Tokens NONE = new Tokens(0, 0);

    public Tokens {
        if (input < 0 || output < 0) {
            throw new IllegalArgumentException("token counts cannot be negative");
        }
    }

    public Tokens plus(Tokens other) {
        return new Tokens(input + other.input, output + other.output);
    }

    public long total() {
        return input + output;
    }

    /**
     * Where the two figures come from, so a reader knows which to trust.
     *
     * <p>A provider that returns no usage on a reply leaves a run reporting fewer tokens
     * than it spent. Reporting that as a measurement would understate a bill, so the
     * distinction is carried rather than flattened.
     */
    public enum Basis { MEASURED, PARTIAL }
}
