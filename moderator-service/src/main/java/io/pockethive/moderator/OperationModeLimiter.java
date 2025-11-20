package io.pockethive.moderator;

import java.util.concurrent.locks.LockSupport;

final class OperationModeLimiter {

  private ModeratorOperationMode.Type activeType = ModeratorOperationMode.Type.PASS_THROUGH;
  private long nextAllowedTimeNanos = System.nanoTime();
  private long sineStartNanos = nextAllowedTimeNanos;

  void await(ModeratorOperationMode mode) {
    if (mode == null) {
      return;
    }
    synchronized (this) {
      long now = System.nanoTime();
      ModeratorOperationMode.Type requestedType = mode.type();
      if (activeType != requestedType) {
        activeType = requestedType;
        reset(now);
      }
      switch (requestedType) {
        case PASS_THROUGH -> reset(now);
        case RATE_PER_SEC -> limitConstantRate(now, ((ModeratorOperationMode.RatePerSec) mode).ratePerSec());
        case SINE -> limitSine(now, (ModeratorOperationMode.Sine) mode);
      }
    }
  }

  private void limitConstantRate(long now, double ratePerSec) {
    if (!Double.isFinite(ratePerSec) || ratePerSec <= 0.0) {
      reset(now);
      return;
    }
    long target = Math.max(nextAllowedTimeNanos, now);
    waitUntil(target);
    updateNext(target, ratePerSec);
  }

  private void limitSine(long now, ModeratorOperationMode.Sine config) {
    long target = Math.max(nextAllowedTimeNanos, now);
    double periodSeconds = config.periodSeconds();
    if (periodSeconds <= 0.0) {
      limitConstantRate(now, config.maxRatePerSec());
      return;
    }
    double elapsedSeconds = (target - sineStartNanos) / 1_000_000_000d;
    double cycles = (elapsedSeconds + config.phaseOffsetSeconds()) / periodSeconds;
    double amplitude = (config.maxRatePerSec() - config.minRatePerSec()) / 2.0;
    double centre = config.minRatePerSec() + amplitude;
    double rate = centre;
    if (amplitude > 0.0) {
      rate = centre + amplitude * Math.sin(2 * Math.PI * cycles);
      rate = clamp(rate, config.minRatePerSec(), config.maxRatePerSec());
    }
    if (!Double.isFinite(rate) || rate <= 0.0) {
      reset(now);
      return;
    }
    limitVariableRate(target, rate);
  }

  private void limitVariableRate(long target, double rate) {
    waitUntil(target);
    updateNext(target, rate);
  }

  private void waitUntil(long targetNanos) {
    long now = System.nanoTime();
    long remaining = targetNanos - now;
    while (remaining > 0L) {
      LockSupport.parkNanos(remaining);
      if (Thread.interrupted()) {
        Thread.currentThread().interrupt();
        break;
      }
      now = System.nanoTime();
      remaining = targetNanos - now;
    }
  }

  private void updateNext(long base, double ratePerSec) {
    if (!Double.isFinite(ratePerSec) || ratePerSec <= 0.0) {
      nextAllowedTimeNanos = base;
      return;
    }
    double interval = 1_000_000_000d / ratePerSec;
    long nanos = (long) Math.max(1L, Math.round(interval));
    long candidate = base + nanos;
    if (candidate < 0) {
      candidate = Long.MAX_VALUE;
    }
    nextAllowedTimeNanos = candidate;
  }

  private static double clamp(double value, double min, double max) {
    if (value < min) {
      return min;
    }
    if (value > max) {
      return max;
    }
    return value;
  }

  private void reset(long now) {
    nextAllowedTimeNanos = now;
    sineStartNanos = now;
  }
}
