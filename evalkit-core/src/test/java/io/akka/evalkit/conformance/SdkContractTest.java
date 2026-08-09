package io.akka.evalkit.conformance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What can be checked about the SDK contract without the SDK.
 *
 * <p>These cases fix the record itself: that every entry names members, that the provenance
 * is complete, and that an entry read from documentation rather than from a declaration says
 * so. None of them can tell whether the members are right &mdash; only the real jars can, and
 * the reflection pass that uses them lives in {@code evalkit-akka}.
 *
 * <p>That division is deliberate. {@code evalkit-core} declares no dependencies and will not
 * acquire one to check a contract, so this half asserts what a pure module can and says
 * plainly that it is the weaker half.
 */
@DisplayName("SDK contract · the assumptions this kit is built on, written down")
class SdkContractTest {

    @Test
    @DisplayName("the provenance is complete enough to fetch the source again")
    void provenanceIsComplete() {
        assertThat(SdkContract.REPO).isEqualTo("akka/akka-sdk");
        assertThat(SdkContract.BRANCH).isNotBlank();
        // Without the full commit a maintainer cannot tell whether the branch has moved
        // since these shapes were copied, which is the whole use of the record.
        assertThat(SdkContract.COMMIT).hasSize(40).matches("[0-9a-f]{40}");
    }

    @Test
    @DisplayName("the check catches an entry that names no member")
    void catchesAnEmptyEntry() {
        var silent = new SdkContract.Entry("akka.javasdk.ledger.Quiet", List.of(),
            SdkContract.Confidence.READ, "");
        var stated = new SdkContract.Entry("akka.javasdk.ledger.Loud", List.of("field"),
            SdkContract.Confidence.READ, "");

        assertThat(SdkContract.empty(List.of(silent, stated)))
            .containsExactly("akka.javasdk.ledger.Quiet");
    }

    @Test
    @DisplayName("every entry names at least one member")
    void everyEntryAssertsSomething() {
        // An entry with no members verifies nothing when the jars arrive, so it would sit
        // in the record looking like coverage while providing none.
        assertThat(SdkContract.empty(SdkContract.ENTRIES))
            .as("entries that would verify nothing")
            .isEmpty();
    }

    @Test
    @DisplayName("every entry names a type in the two packages this kit targets")
    void everyEntryIsInScope() {
        assertThat(SdkContract.ENTRIES).allSatisfy(entry ->
            assertThat(entry.type())
                .startsWith("akka.javasdk.")
                .matches("akka\\.javasdk\\.(evaluation|ledger)\\.[A-Z]\\w+"));
    }

    @Test
    @DisplayName("no type is recorded twice")
    void entriesAreDistinct() {
        assertThat(SdkContract.ENTRIES.stream().map(SdkContract.Entry::type).toList())
            .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the check catches an entry that is still an inference")
    void catchesAnInferredEntry() {
        var guessed = new SdkContract.Entry("akka.javasdk.ledger.Guessed", List.of("maybe"),
            SdkContract.Confidence.INFERRED, "");
        var read = new SdkContract.Entry("akka.javasdk.ledger.Known", List.of("certain"),
            SdkContract.Confidence.READ, "");

        assertThat(SdkContract.inferred(List.of(guessed, read)))
            .containsExactly("akka.javasdk.ledger.Guessed");
    }

    @Test
    @DisplayName("nothing is an inference any more, because the jars have been read")
    void nothingIsInferred() {
        // Both entries that were inferred have since been checked against the published
        // classes. One of the two was wrong, which is what the record exists to surface.
        assertThat(SdkContract.inferred(SdkContract.ENTRIES))
            .as("assumptions still taken from documentation rather than a declaration")
            .isEmpty();
    }

    @Test
    @DisplayName("every evalkit type claimed as shaped by an entry exists")
    void shapedTypesExist() {
        for (SdkContract.Entry entry : SdkContract.ENTRIES) {
            if (entry.shapes().isEmpty()) continue;
            assertThat(classExists(entry.shapes()))
                .as(entry.shapes() + ", claimed to be shaped after " + entry.type())
                .isTrue();
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
