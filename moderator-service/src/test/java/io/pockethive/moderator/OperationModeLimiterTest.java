package io.pockethive.moderator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OperationModeLimiterTest {

  @Test
  void passThroughClearsFutureScheduleAfterSlowRate() {
    OperationModeLimiter limiter = new OperationModeLimiter();

    // First, set a very slow rate to push nextAllowedTime far into the future.
    limiter.await(ModeratorOperationMode.ratePerSec(0.1)); // interval ~10s

    // Switching to pass-through should clear any future target.
    limiter.await(ModeratorOperationMode.passThrough());

    long start = System.nanoTime();
    limiter.await(ModeratorOperationMode.ratePerSec(1000)); // should not inherit the 10s delay
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

    assertThat(elapsedMillis).isLessThan(50);
  }

  @Test
  void zeroRateActsAsPassThrough() {
    OperationModeLimiter limiter = new OperationModeLimiter();

    long start = System.nanoTime();
    limiter.await(ModeratorOperationMode.ratePerSec(0.0));
    limiter.await(ModeratorOperationMode.ratePerSec(0.0));
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

    assertThat(elapsedMillis).isLessThan(50);
  }
}
