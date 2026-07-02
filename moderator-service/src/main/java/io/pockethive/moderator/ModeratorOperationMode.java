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
      ratePerSec = requireNonNegative(ratePerSec, "ratePerSec");
    }

    @Override
    public Type type() {
      return Type.RATE_PER_SEC;
    }
  }

  record Sine(double minRatePerSec, double maxRatePerSec, double periodSeconds, double phaseOffsetSeconds)
      implements ModeratorOperationMode {
    public Sine {
      minRatePerSec = requireNonNegative(minRatePerSec, "minRatePerSec");
      maxRatePerSec = requireNonNegative(maxRatePerSec, "maxRatePerSec");
      if (maxRatePerSec < minRatePerSec) {
        throw new IllegalArgumentException("maxRatePerSec must be greater than or equal to minRatePerSec");
      }
      periodSeconds = requirePositive(periodSeconds, "periodSeconds");
      phaseOffsetSeconds = requireFinite(phaseOffsetSeconds, "phaseOffsetSeconds");
    }

    @Override
    public Type type() {
      return Type.SINE;
    }
  }

  private static double requireNonNegative(double candidate, String field) {
    if (!Double.isFinite(candidate) || candidate < 0.0) {
      throw new IllegalArgumentException(field + " must be finite and non-negative");
    }
    return candidate;
  }

  private static double requirePositive(double candidate, String field) {
    if (!Double.isFinite(candidate) || candidate <= 0.0) {
      throw new IllegalArgumentException(field + " must be finite and positive");
    }
    return candidate;
  }

  private static double requireFinite(double candidate, String field) {
    if (!Double.isFinite(candidate)) {
      throw new IllegalArgumentException(field + " must be finite");
    }
    return candidate;
  }
}
