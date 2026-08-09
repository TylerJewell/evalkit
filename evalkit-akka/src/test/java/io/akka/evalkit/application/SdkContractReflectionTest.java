package io.akka.evalkit.application;

import io.akka.evalkit.conformance.SdkContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every assumption in {@link SdkContract}, checked against the real classes.
 *
 * <p>The shapes in {@code evalkit-core} were copied from source on an unmerged branch, which
 * is a guess until something compares it with the jars. This is that comparison: it loads
 * each recorded type and looks for each recorded member among its methods, fields and nested
 * types.
 *
 * <p>A failure here is not a defect in the SDK. It means this kit was shaped against
 * something that has since moved, and the entry naming the missing member says where to look.
 */
@DisplayName("SDK contract · the recorded assumptions against the published jars")
class SdkContractReflectionTest {

    /** Members of a type as a name set: methods, fields, record components and nested types. */
    private static Set<String> membersOf(Class<?> type) {
        var names = new TreeSet<String>();
        Arrays.stream(type.getMethods()).map(Method::getName).forEach(names::add);
        Arrays.stream(type.getDeclaredMethods()).map(Method::getName).forEach(names::add);
        Arrays.stream(type.getDeclaredFields()).forEach(field -> names.add(field.getName()));
        Arrays.stream(type.getDeclaredClasses()).forEach(nested -> names.add(nested.getSimpleName()));
        if (type.isRecord()) {
            Arrays.stream(type.getRecordComponents())
                .forEach(component -> names.add(component.getName()));
        }
        return names;
    }

    @Test
    @DisplayName("every recorded type is on the classpath")
    void everyTypeResolves() {
        var missing = new ArrayList<String>();
        for (SdkContract.Entry entry : SdkContract.ENTRIES) {
            try {
                Class.forName(entry.type());
            } catch (ClassNotFoundException e) {
                missing.add(entry.type());
            }
        }

        assertThat(missing)
            .as("types recorded in SdkContract that this SDK build does not have")
            .isEmpty();
    }

    @Test
    @DisplayName("every recorded member exists on the type that is supposed to have it")
    void everyMemberResolves() throws Exception {
        var wrong = new ArrayList<String>();
        int checked = 0;

        for (SdkContract.Entry entry : SdkContract.ENTRIES) {
            var members = membersOf(Class.forName(entry.type()));
            for (String member : entry.members()) {
                checked++;
                if (!members.contains(member)) {
                    wrong.add(entry.type() + "." + member + "  (" + entry.confidence() + ")");
                }
            }
        }

        // Asserted before the comparison. An empty contract would produce an empty failure
        // list and read exactly like a contract that matched.
        assertThat(checked).as("members actually compared").isGreaterThan(50);
        assertThat(wrong)
            .as("assumptions in SdkContract that the published SDK does not bear out")
            .isEmpty();
    }

    @Test
    @DisplayName("the check catches a member that does not exist")
    void catchesAMissingMember() {
        // The case this test exists for. Without it, a reflection pass that silently found
        // nothing would look the same as one that verified everything.
        var members = membersOf(SdkContract.Entry.class);

        assertThat(members).contains("type", "members", "confidence", "shapes");
        assertThat(members).doesNotContain("aMemberNobodyDeclared");
    }

    @Test
    @DisplayName("the commit the shapes were read at is recorded in full")
    void theRecordedCommitIsStated() {
        // The jar carries no commit, so this cannot be checked against it. Stated here so a
        // failure above is read alongside the revision the shapes were written against.
        assertThat(SdkContract.COMMIT).hasSize(40).matches("[0-9a-f]{40}");
        assertThat(SdkContract.BRANCH).isEqualTo("feature/governance");
    }

    @Test
    @DisplayName("every entry has now been read against a declaration, not inferred")
    void nothingIsInferred() {
        // This test is what removed the last two inferences. WorkflowEvaluator's handler is
        // onEvaluation and not evaluate, which no amount of reading the class documentation
        // would have revealed.
        assertThat(SdkContract.inferred(SdkContract.ENTRIES)).isEmpty();
    }

    @Test
    @DisplayName("the evalkit types claimed as shaped after an SDK type all exist")
    void shapedTypesResolve() {
        var claimed = SdkContract.ENTRIES.stream()
            .map(SdkContract.Entry::shapes)
            .filter(name -> !name.isEmpty())
            .collect(Collectors.toCollection(TreeSet::new));

        assertThat(claimed).isNotEmpty();
        for (String name : claimed) {
            try {
                Class.forName(name);
            } catch (ClassNotFoundException e) {
                throw new AssertionError("shaped type missing: " + name, e);
            }
        }
    }

    /** The contract, for a reader of a failure. */
    @Test
    @DisplayName("report what is being checked")
    void report() {
        var byConfidence = SdkContract.ENTRIES.stream()
            .collect(Collectors.groupingBy(SdkContract.Entry::confidence,
                Collectors.counting()));
        int members = SdkContract.ENTRIES.stream().mapToInt(e -> e.members().size()).sum();

        System.out.printf("%nSDK contract: %d types, %d members, %s%n",
            SdkContract.ENTRIES.size(), members, byConfidence);
        System.out.printf("recorded at %s %s @ %s%n",
            SdkContract.REPO, SdkContract.BRANCH, SdkContract.COMMIT.substring(0, 12));

        assertThat(SdkContract.ENTRIES).hasSize(14);
    }
}
