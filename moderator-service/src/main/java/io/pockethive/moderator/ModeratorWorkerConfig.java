package io.pockethive.moderator;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record ModeratorWorkerConfig(Mode mode) {

  public ModeratorWorkerConfig {
    Objects.requireNonNull(mode, "mode");
  }

  public ModeratorOperationMode operationMode() {
    return mode.toOperationMode();
  }

  public record Mode(Type type, double ratePerSec, Sine sine) {

    private static final Logger log = LoggerFactory.getLogger(Mode.class);

    public Mode {
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(sine, "sine");
      ratePerSec = sanitiseRate(ratePerSec);
    }

    static Mode passThrough() {
      return new Mode(Type.PASS_THROUGH, 0.0, Sine.DEFAULT);
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

      private static final Logger log = LoggerFactory.getLogger(Type.class);

      @JsonCreator
      public static Type fromString(String raw) {
        if (raw == null) {
          log.warn("Missing moderator mode type; defaulting to pass-through");
          return PASS_THROUGH;
        }
        String normalized = raw.trim();
        if (normalized.isEmpty()) {
          log.warn("Blank moderator mode type; defaulting to pass-through");
          return PASS_THROUGH;
        }
        normalized = normalized.replace('-', '_').replace(' ', '_');
        try {
          return Type.valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
          log.warn("Unsupported moderator mode type '{}'; defaulting to pass-through", raw);
          return PASS_THROUGH;
        }
      }

      @JsonValue
      public String jsonValue() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
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
