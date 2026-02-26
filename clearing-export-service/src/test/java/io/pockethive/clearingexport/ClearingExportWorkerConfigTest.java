package io.pockethive.clearingexport;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClearingExportWorkerConfigTest {

  @Test
  void defaultConstructorUsesStopFailurePolicy() {
    ClearingExportWorkerConfig config = new ClearingExportWorkerConfig(
        "template",
        false,
        21_600_000L,
        1000,
        1000L,
        50_000,
        true,
        "\n",
        "out.dat",
        "H|{{ now }}",
        "D|{{ record.payload }}",
        "T|{{ recordCount }}",
        "/tmp/pockethive/clearing-out",
        ".tmp",
        false,
        "reports/clearing/manifest.jsonl",
        "/tmp/schemas",
        null,
        null
    );

    assertThat(config.recordBuildFailurePolicy()).isEqualTo("stop");
    assertThat(config.recordSourceStep()).isEqualTo("latest");
    assertThat(config.recordSourceStepIndex()).isEqualTo(-1);
  }

  @Test
  void rejectsUnknownRecordSourceStep() {
    assertThatThrownBy(() -> config("middle", -1, "stop"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recordSourceStep must be one of");
  }

  @Test
  void rejectsIndexModeWithNegativeStepIndex() {
    assertThatThrownBy(() -> config("index", -1, "stop"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recordSourceStep=index requires recordSourceStepIndex >= 0");
  }

  @Test
  void rejectsStepIndexBelowMinusOneForNonIndexMode() {
    assertThatThrownBy(() -> config("latest", -2, "stop"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recordSourceStepIndex must be >= -1");
  }

  @Test
  void rejectsUnknownRecordBuildFailurePolicy() {
    assertThatThrownBy(() -> config("latest", -1, "warn_only"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recordBuildFailurePolicy must be one of");
  }

  @Test
  void acceptsIndexModeWithMatchingStepIndex() {
    ClearingExportWorkerConfig config = config("index", 2, "stop");

    assertThat(config.recordSourceStep()).isEqualTo("index");
    assertThat(config.recordSourceStepIndex()).isEqualTo(2);
    assertThat(config.recordBuildFailurePolicy()).isEqualTo("stop");
  }

  private static ClearingExportWorkerConfig config(
      String recordSourceStep,
      Integer recordSourceStepIndex,
      String recordBuildFailurePolicy
  ) {
    return new ClearingExportWorkerConfig(
        "template",
        false,
        21_600_000L,
        1000,
        1000L,
        50_000,
        true,
        "\n",
        "out.dat",
        "H|{{ now }}",
        "D|{{ record.payload }}",
        "T|{{ recordCount }}",
        "/tmp/pockethive/clearing-out",
        ".tmp",
        false,
        "reports/clearing/manifest.jsonl",
        "/tmp/schemas",
        null,
        null,
        recordSourceStep,
        recordSourceStepIndex,
        recordBuildFailurePolicy
    );
  }
}
