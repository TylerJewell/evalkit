package io.akka.evalkit.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The calibration sample reader, which runs without a model or a key.
 *
 * <p>{@link JudgeCalibrationTest} calls a live model and stays disabled unless
 * {@code -Dcalibration=true} is set, so its reader would otherwise ship untested. A
 * reference score read as zero is the failure that matters here: Jackson's
 * {@code path().asDouble()} returns 0 for an absent field, 0 is a valid band, and the
 * agreement figure moves with nothing in the output to show a field was renamed.
 */
@DisplayName("Calibration sample · a reference score is required, never defaulted")
class CalibrationSampleTest {

    private static final String TRANSCRIPT = """
        "dataset": "claims", "scenario_name": "refund-30d", \
        "replay_history": "", "simulation_history": "", \
        "system_output": "Refused, 30-day window", "expected_outcome": "Refuses"\
        """;

    @Test
    @DisplayName("a line carrying a reference score parses")
    void readsAScore(@TempDir Path dir) throws Exception {
        var samples = JudgeCalibrationTest.load(
            write(dir, "{" + TRANSCRIPT + ", \"reference_score\": 9}"));

        assertThat(samples).hasSize(1);
        assertThat(samples.get(0).reference()).isEqualTo(9);
        assertThat(samples.get(0).dataset()).isEqualTo("claims");
    }

    @Test
    @DisplayName("a line with no reference score fails, naming the line")
    void missingScoreFails(@TempDir Path dir) throws Exception {
        var path = write(dir,
            "{" + TRANSCRIPT + ", \"reference_score\": 9}",
            "{" + TRANSCRIPT + "}");

        assertThatThrownBy(() -> JudgeCalibrationTest.load(path))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("line 2")
            .hasMessageContaining("reference_score");
    }

    @Test
    @DisplayName("a reference score that is not a number fails")
    void nonNumericScoreFails(@TempDir Path dir) throws Exception {
        var path = write(dir, "{" + TRANSCRIPT + ", \"reference_score\": \"nine\"}");

        assertThatThrownBy(() -> JudgeCalibrationTest.load(path))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reference_score");
    }

    @Test
    @DisplayName("a blank line is skipped and does not shift the reported line number")
    void blankLinesAreSkipped(@TempDir Path dir) throws Exception {
        var path = write(dir,
            "{" + TRANSCRIPT + ", \"reference_score\": 9}",
            "",
            "{" + TRANSCRIPT + "}");

        assertThatThrownBy(() -> JudgeCalibrationTest.load(path))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("line 3");
    }

    private static Path write(Path dir, String... lines) throws Exception {
        var path = dir.resolve("sample.jsonl");
        Files.write(path, List.of(lines));
        return path;
    }
}
