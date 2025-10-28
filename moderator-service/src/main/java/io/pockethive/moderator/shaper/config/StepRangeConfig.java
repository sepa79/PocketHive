package io.pockethive.moderator.shaper.config;

import java.math.BigDecimal;
import java.time.Duration;

public record StepRangeConfig(StepRangeUnit unit,
                              String start,
                              String end,
                              BigDecimal startPct,
                              BigDecimal endPct) {

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  public StepRangeConfig {
    unit = unit == null ? StepRangeUnit.PERCENT : unit;
    if (unit == StepRangeUnit.CLOCK) {
      if (start == null || start.isBlank() || end == null || end.isBlank()) {
        throw new IllegalArgumentException("clock ranges require start and end times");
      }
      int startMinutes = parseClockMinutes(start);
      int endMinutes = parseClockMinutes(end);
      if (startMinutes < 0 || endMinutes < 0 || endMinutes <= startMinutes) {
        throw new IllegalArgumentException("clock range must have start < end within 24h");
      }
    } else {
      if (startPct == null || endPct == null) {
        throw new IllegalArgumentException("percent ranges require startPct and endPct");
      }
      if (startPct.compareTo(BigDecimal.ZERO) < 0 || endPct.compareTo(HUNDRED) > 0
          || endPct.compareTo(startPct) <= 0) {
        throw new IllegalArgumentException("percent range must satisfy 0 <= start < end <= 100");
      }
    }
  }

  public double startFraction(Duration patternDuration) {
    return switch (unit) {
      case CLOCK -> toClockFraction(start, patternDuration);
      case PERCENT -> startPct.divide(HUNDRED).doubleValue();
    };
  }

  public double endFraction(Duration patternDuration) {
    return switch (unit) {
      case CLOCK -> toClockFraction(end, patternDuration);
      case PERCENT -> endPct.divide(HUNDRED).doubleValue();
    };
  }

  public double spanFraction(Duration patternDuration) {
    return endFraction(patternDuration) - startFraction(patternDuration);
  }

  private static double toClockFraction(String value, Duration patternDuration) {
    long totalMillis = patternDuration.toMillis();
    if (totalMillis <= 0) {
      throw new IllegalArgumentException("pattern duration must be positive");
    }
    long minutes = parseClockMinutes(value);
    return (minutes * 60_000d) / totalMillis;
  }

  private static int parseClockMinutes(String text) {
    String[] parts = text.split(":");
    if (parts.length < 2 || parts.length > 3) {
      throw new IllegalArgumentException("clock time must be HH:mm or HH:mm:ss");
    }
    int hours = Integer.parseInt(parts[0]);
    int minutes = Integer.parseInt(parts[1]);
    int seconds = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
    if (hours < 0 || hours > 24) {
      throw new IllegalArgumentException("clock hour must be between 0 and 24");
    }
    if ((hours == 24 && (minutes > 0 || seconds > 0)) || minutes < 0 || minutes >= 60 || seconds < 0 || seconds >= 60) {
      throw new IllegalArgumentException("clock time must be within a single day");
    }
    return hours * 60 + minutes + (seconds >= 30 ? 1 : 0);
  }
}
