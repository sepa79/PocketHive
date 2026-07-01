package io.pockethive.moderator;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Objects;

public record ModeratorWorkerConfig(Mode mode) {

  public ModeratorWorkerConfig {
    mode = Objects.requireNonNull(mode, "mode");
  }

  public ModeratorOperationMode operationMode() {
    return mode.toOperationMode();
  }

  public record Mode(Type type, Double ratePerSec, Sine sine) {

    public Mode {
      Objects.requireNonNull(type, "type");
      if (type == Type.RATE_PER_SEC) {
        ratePerSec = requireNonNegative(ratePerSec, "ratePerSec");
      }
      if (type == Type.SINE) {
        sine = Objects.requireNonNull(sine, "sine");
      }
    }

    ModeratorOperationMode toOperationMode() {
      return switch (type) {
        case PASS_THROUGH -> ModeratorOperationMode.passThrough();
        case RATE_PER_SEC -> ModeratorOperationMode.ratePerSec(ratePerSec);
        case SINE -> ModeratorOperationMode.sine(
            sine.minRatePerSec(),
            sine.maxRatePerSec(),
            sine.periodSeconds(),
            sine.phaseOffsetSeconds());
      };
    }

    public enum Type {
      PASS_THROUGH,
      RATE_PER_SEC,
      SINE;

      @JsonCreator
      public static Type fromString(String raw) {
        if (raw == null) {
          throw new IllegalArgumentException("moderator mode type must be provided");
        }
        String normalized = raw.trim();
        if (normalized.isEmpty()) {
          throw new IllegalArgumentException("moderator mode type must not be blank");
        }
        normalized = normalized.replace('-', '_').replace(' ', '_');
        try {
          return Type.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
          throw new IllegalArgumentException("unsupported moderator mode type: " + raw, ex);
        }
      }

      @JsonValue
      public String jsonValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
      }
    }

    public record Sine(Double minRatePerSec, Double maxRatePerSec, Double periodSeconds, Double phaseOffsetSeconds) {
      public Sine {
        minRatePerSec = requireNonNegative(minRatePerSec, "sine.minRatePerSec");
        maxRatePerSec = requireNonNegative(maxRatePerSec, "sine.maxRatePerSec");
        if (maxRatePerSec < minRatePerSec) {
          throw new IllegalArgumentException("sine.maxRatePerSec must be greater than or equal to sine.minRatePerSec");
        }
        periodSeconds = requirePositive(periodSeconds, "sine.periodSeconds");
        phaseOffsetSeconds = requireFinite(phaseOffsetSeconds, "sine.phaseOffsetSeconds");
      }
    }
  }

  private static double requireNonNegative(Double candidate, String field) {
    if (candidate == null || !Double.isFinite(candidate) || candidate < 0.0) {
      throw new IllegalArgumentException(field + " must be finite and non-negative");
    }
    return candidate;
  }

  private static double requirePositive(Double candidate, String field) {
    if (candidate == null || !Double.isFinite(candidate) || candidate <= 0.0) {
      throw new IllegalArgumentException(field + " must be finite and positive");
    }
    return candidate;
  }

  private static double requireFinite(Double candidate, String field) {
    if (candidate == null || !Double.isFinite(candidate)) {
      throw new IllegalArgumentException(field + " must be finite");
    }
    return candidate;
  }
}
