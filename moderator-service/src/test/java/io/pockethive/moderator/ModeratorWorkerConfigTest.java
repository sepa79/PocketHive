package io.pockethive.moderator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModeratorWorkerConfigTest {

  @Test
  void defaultsToPassThroughWhenModeMissing() {
    ModeratorWorkerConfig config = new ModeratorWorkerConfig(null);

    assertThat(config.operationMode()).isInstanceOf(ModeratorOperationMode.PassThrough.class);
  }

  @Test
  void ratePerSecSanitisesNegativeValues() {
    ModeratorWorkerConfig.Mode mode = new ModeratorWorkerConfig.Mode(
        ModeratorWorkerConfig.Mode.Type.RATE_PER_SEC,
        new ModeratorWorkerConfig.Mode.RatePerSec(-10.0),
        new ModeratorWorkerConfig.Mode.Sine(0.0, 0.0, 60.0, 0.0));

    ModeratorOperationMode operationMode = new ModeratorWorkerConfig(mode).operationMode();

    assertThat(operationMode)
        .isInstanceOf(ModeratorOperationMode.RatePerSec.class)
        .extracting("ratePerSec")
        .isEqualTo(0.0);
  }

  @Test
  void sineModeSwapsMisorderedBoundsAndDefaultsPeriod() {
    ModeratorWorkerConfig.Mode mode = new ModeratorWorkerConfig.Mode(
        ModeratorWorkerConfig.Mode.Type.SINE,
        new ModeratorWorkerConfig.Mode.RatePerSec(0.0),
        new ModeratorWorkerConfig.Mode.Sine(5.0, 1.0, 0.0, Double.NaN));

    ModeratorOperationMode operationMode = new ModeratorWorkerConfig(mode).operationMode();

    assertThat(operationMode)
        .isInstanceOf(ModeratorOperationMode.Sine.class)
        .satisfies(op -> {
          ModeratorOperationMode.Sine sine = (ModeratorOperationMode.Sine) op;
          assertThat(sine.minRatePerSec()).isEqualTo(1.0);
          assertThat(sine.maxRatePerSec()).isEqualTo(5.0);
          assertThat(sine.periodSeconds()).isEqualTo(60.0);
          assertThat(sine.phaseOffsetSeconds()).isEqualTo(0.0);
        });
  }

}
