package io.akka.evalkit.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The rules the system under test was operating under, as versioned data.
 *
 * <p>A campaign measures a system against what it was told to do. Change the instruction
 * and the same corpus, the same rubric and the same code produce different results, with
 * nothing in the numbers to say why. {@link Rubric} is versioned because it defines the
 * ruler; this is versioned because it defines what was being measured.
 *
 * <p>The distinction from a rubric is worth keeping straight. A rubric conditions the
 * verdict and travels with it. A policy conditions the run, so it belongs to the campaign
 * and is stated once in the report rather than repeated on every row.
 *
 * <p>The text lives in {@code resources/policies/} and loads by id and version, so a run
 * recorded months ago can be read against the rules it actually ran under.
 */
public record Policy(String id, int version, String text) {

    public Policy {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("policy id required");
        if (version < 1) throw new IllegalArgumentException("policy version starts at 1");
        if (text == null || text.isBlank()) {
            // A policy with no text names rules nobody can read. The label would still
            // appear in the report and still gate comparison, which is worse than absent.
            throw new IllegalArgumentException("policy " + id + " v" + version + " has no text");
        }
    }

    /** Loads {@code policies/<id>-v<version>.md} from the classpath. */
    public static Policy load(String id, int version) {
        String path = "policies/" + id + "-v" + version + ".md";
        try (InputStream in = Policy.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("no policy at " + path);
            return new Policy(id, version, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }

    /** How the report and any refusal name this policy. */
    public String label() {
        return id + " v" + version;
    }
}
