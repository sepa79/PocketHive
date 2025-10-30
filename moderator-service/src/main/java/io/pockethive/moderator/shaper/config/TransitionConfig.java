package io.pockethive.moderator.shaper.config;

import java.math.BigDecimal;
import java.time.Duration;

public record TransitionConfig(TransitionType type, Duration duration, BigDecimal percent) {

  public TransitionConfig {
    type = type == null ? TransitionType.NONE : type;
    if (type == TransitionType.NONE) {
      duration = null;
      percent = null;
    }
    if (duration != null && (duration.isNegative() || duration.isZero())) {
      throw new IllegalArgumentException("transition duration must be positive when provided");
    }
    if (percent != null && percent.compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException("transition percent must be positive");
    }
  }

  public static TransitionConfig none() {
    return new TransitionConfig(TransitionType.NONE, null, null);
  }
}
