package io.pockethive.acceptance.operations;

import java.time.Duration;

/**
 * Responsibility: bound one observation wait using monotonic elapsed time.
 * Must not: reset budgets, retry mutations or decide readiness.
 * Contract: RESP-ACCEPTANCE-OPERATIONS — docs/architecture/acceptance-tests.md#resp-acceptance-operations.
 */
public final class Deadline {
  private final long started = System.nanoTime();
  private final Duration budget;
  private final String description;
  public Deadline(Duration budget, String description) {
    if (budget.isZero() || budget.isNegative()) throw new IllegalArgumentException("Wait budget must be positive");
    budget.toNanos();
    this.budget = budget;
    this.description = description;
  }
  public Duration remainingOrZero() {
    return Duration.ofNanos(Math.max(0L, budget.toNanos() - (System.nanoTime() - started)));
  }
  public Duration remaining() {
    long remaining = remainingOrZero().toNanos();
    if (remaining <= 0) throw new AssertionError(description + " timed out after " + budget);
    return Duration.ofNanos(remaining);
  }
  public void pause(Duration interval) throws InterruptedException {
    Duration left = remaining();
    Thread.sleep(interval.compareTo(left) < 0 ? interval : left);
  }
}
