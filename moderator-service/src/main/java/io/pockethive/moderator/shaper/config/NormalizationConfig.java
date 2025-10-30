package io.pockethive.moderator.shaper.config;

import java.math.BigDecimal;

public record NormalizationConfig(boolean enabled, BigDecimal tolerancePct) {

  public NormalizationConfig {
    if (enabled) {
      if (tolerancePct == null) {
        throw new IllegalArgumentException("normalization tolerance must be provided when enabled");
      }
    }
  }

  public static NormalizationConfig disabled() {
    return new NormalizationConfig(false, null);
  }
}
