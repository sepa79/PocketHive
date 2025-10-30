package io.pockethive.moderator;

sealed interface ModeratorOperationMode permits ModeratorOperationMode.PassThrough, ModeratorOperationMode.RatePerSec, ModeratorOperationMode.Sine {

  enum Type {
    PASS_THROUGH,
    RATE_PER_SEC,
    SINE
  }

  Type type();

  static PassThrough passThrough() {
    return new PassThrough();
  }

  static RatePerSec ratePerSec(double ratePerSec) {
    return new RatePerSec(ratePerSec);
  }

  static Sine sine(double minRatePerSec, double maxRatePerSec, double periodSeconds, double phaseOffsetSeconds) {
    return new Sine(minRatePerSec, maxRatePerSec, periodSeconds, phaseOffsetSeconds);
  }

  record PassThrough() implements ModeratorOperationMode {
    @Override
    public Type type() {
      return Type.PASS_THROUGH;
    }
  }

  record RatePerSec(double ratePerSec) implements ModeratorOperationMode {
    public RatePerSec {
      double sanitised = sanitiseRate(ratePerSec);
      ratePerSec = sanitised;
    }

    @Override
    public Type type() {
      return Type.RATE_PER_SEC;
    }
  }

  record Sine(double minRatePerSec, double maxRatePerSec, double periodSeconds, double phaseOffsetSeconds)
      implements ModeratorOperationMode {
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

    @Override
    public Type type() {
      return Type.SINE;
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
