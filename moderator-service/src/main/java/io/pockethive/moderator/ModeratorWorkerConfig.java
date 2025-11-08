package io.pockethive.moderator;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public record ModeratorWorkerConfig(boolean enabled, Mode mode) {

  public ModeratorWorkerConfig {
    mode = mode == null ? Mode.passThrough() : mode;
  }

  public ModeratorOperationMode operationMode() {
    return mode.toOperationMode();
  }

  public record Mode(Type type, RatePerSec ratePerSec, Sine sine) {

    public Mode {
      type = type == null ? Type.PASS_THROUGH : type;
      ratePerSec = ratePerSec == null ? RatePerSec.DEFAULT : ratePerSec;
      sine = sine == null ? Sine.DEFAULT : sine;
    }

    static Mode passThrough() {
      return new Mode(Type.PASS_THROUGH, RatePerSec.DEFAULT, Sine.DEFAULT);
    }

    ModeratorOperationMode toOperationMode() {
      return switch (type) {
        case PASS_THROUGH -> ModeratorOperationMode.passThrough();
        case RATE_PER_SEC -> ModeratorOperationMode.ratePerSec(ratePerSec.value());
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
          return PASS_THROUGH;
        }
        String normalized = raw.trim();
        if (normalized.isEmpty()) {
          return PASS_THROUGH;
        }
        normalized = normalized.replace('-', '_').replace(' ', '_');
        try {
          return Type.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
          throw new IllegalArgumentException(
              "Unsupported moderator mode type '%s'".formatted(raw), ex);
        }
      }

      @JsonValue
      public String jsonValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
      }
    }

    public record RatePerSec(double value) {
      static final RatePerSec DEFAULT = new RatePerSec(0.0);

      public RatePerSec {
        value = sanitiseRate(value);
      }
    }

    public record Sine(double minRatePerSec, double maxRatePerSec, double periodSeconds, double phaseOffsetSeconds) {
      static final Sine DEFAULT = new Sine(0.0, 0.0, 60.0, 0.0);

      public Sine {
        double sanitisedMin = sanitiseRate(minRatePerSec);
        double sanitisedMax = sanitiseRate(maxRatePerSec);
        if (sanitisedMax < sanitisedMin) {
          double tmp = sanitisedMin;
          sanitisedMin = sanitisedMax;
          sanitisedMax = tmp;
        }
        double sanitisedPeriod = sanitisePositive(periodSeconds, 60.0);
        double sanitisedPhase = Double.isFinite(phaseOffsetSeconds) ? phaseOffsetSeconds : 0.0;
        minRatePerSec = sanitisedMin;
        maxRatePerSec = sanitisedMax;
        periodSeconds = sanitisedPeriod;
        phaseOffsetSeconds = sanitisedPhase;
      }
    }
  }

  private static double sanitiseRate(double candidate) {
    if (!Double.isFinite(candidate) || candidate < 0.0) {
      return 0.0;
    }
    return candidate;
  }

  private static double sanitisePositive(double candidate, double fallback) {
    if (!Double.isFinite(candidate) || candidate <= 0.0) {
      return fallback;
    }
    return candidate;
  }
}
