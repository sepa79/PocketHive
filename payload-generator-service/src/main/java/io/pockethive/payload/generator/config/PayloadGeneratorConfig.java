package io.pockethive.payload.generator.config;

import io.pockethive.worker.sdk.input.SchedulerStates;
import java.util.Objects;

public record PayloadGeneratorConfig(double ratePerSec, boolean singleRequest)
    implements SchedulerStates.RateConfig {

  public PayloadGeneratorConfig {
    ratePerSec = Double.isNaN(ratePerSec) || ratePerSec < 0 ? 0.0 : ratePerSec;
  }

  public PayloadGeneratorConfig() {
    this(0.0, false);
  }

    public static PayloadGeneratorConfig of(double ratePerSec, boolean singleRequest) {
        return new PayloadGeneratorConfig(ratePerSec, singleRequest);
    }

    public static PayloadGeneratorConfig copyOf(SchedulerStates.RateConfig config) {
        Objects.requireNonNull(config, "config");
        return new PayloadGeneratorConfig(config.ratePerSec(), config.singleRequest());
    }
}
