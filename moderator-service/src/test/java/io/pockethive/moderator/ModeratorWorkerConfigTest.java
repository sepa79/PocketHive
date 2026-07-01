package io.pockethive.moderator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModeratorWorkerConfigTest {

  @Test
  void rejectsMissingMode() {
    assertThatThrownBy(() -> new ModeratorWorkerConfig(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("mode");
  }

  @Test
  void rejectsNegativeRatePerSec() {
    assertThatThrownBy(() -> new ModeratorWorkerConfig.Mode(
        ModeratorWorkerConfig.Mode.Type.RATE_PER_SEC,
        -10.0,
        null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ratePerSec must be finite and non-negative");
  }

  @Test
  void rejectsMisorderedSineBounds() {
    assertThatThrownBy(() -> new ModeratorWorkerConfig.Mode.Sine(5.0, 1.0, 60.0, 0.0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("sine.maxRatePerSec must be greater than or equal to sine.minRatePerSec");
  }

  @Test
  void acceptsExplicitPassThroughMode() {
    ModeratorWorkerConfig config = new ModeratorWorkerConfig(new ModeratorWorkerConfig.Mode(
        ModeratorWorkerConfig.Mode.Type.PASS_THROUGH,
        null,
        null));

    assertThat(config.operationMode()).isInstanceOf(ModeratorOperationMode.PassThrough.class);
  }

}
