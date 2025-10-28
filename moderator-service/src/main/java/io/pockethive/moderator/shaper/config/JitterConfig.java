package io.pockethive.moderator.shaper.config;

import java.time.Duration;

public record JitterConfig(JitterType type, Duration max, String seed, Duration period) {

  public JitterConfig {
    type = type == null ? JitterType.NONE : type;
    if (type == JitterType.PERIODIC && (period == null || period.isNegative() || period.isZero())) {
      throw new IllegalArgumentException("period must be positive for periodic jitter");
    }
    if (max != null && (max.isNegative() || max.isZero())) {
      throw new IllegalArgumentException("max jitter must be positive when provided");
    }
  }

  public static JitterConfig disabled() {
    return new JitterConfig(JitterType.NONE, null, null, null);
  }
}
