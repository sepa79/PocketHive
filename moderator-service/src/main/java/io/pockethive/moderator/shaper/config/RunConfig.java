package io.pockethive.moderator.shaper.config;

import java.time.Duration;

public record RunConfig(Duration totalTime) {

  private static final Duration DEFAULT_TOTAL = Duration.ofDays(1);

  public RunConfig {
    totalTime = totalTime == null ? DEFAULT_TOTAL : totalTime;
    if (totalTime.isZero() || totalTime.isNegative()) {
      throw new IllegalArgumentException("totalTime must be positive");
    }
  }
}
