package io.pockethive.moderator.shaper.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

public record PatternConfig(Duration duration,
                            BigDecimal baseRateRps,
                            RepeatConfig repeat,
                            List<StepConfig> steps) {

  public PatternConfig {
    duration = duration == null ? Duration.ofHours(24) : duration;
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("pattern duration must be positive");
    }
    baseRateRps = baseRateRps == null ? BigDecimal.ZERO : baseRateRps;
    if (baseRateRps.compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalArgumentException("baseRateRps must be positive");
    }
    repeat = repeat == null ? new RepeatConfig(true, RepeatUntil.TOTAL_TIME, null, RepeatAlignment.FROM_START) : repeat;
    steps = steps == null || steps.isEmpty() ? List.of() : List.copyOf(steps);
  }
}
