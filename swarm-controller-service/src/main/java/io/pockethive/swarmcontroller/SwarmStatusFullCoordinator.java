package io.pockethive.swarmcontroller;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Responsibility: Coordinate one-shot startup and post-lifecycle full-status publication triggers.
 * Must not: Build status payloads, mutate lifecycle state, or decide lifecycle command outcomes.
 * Contract: Publish once when startup becomes ready and after lifecycle freshness or the explicit timeout.
 */
final class SwarmStatusFullCoordinator {

  private static final long POST_LIFECYCLE_TIMEOUT_MS = 5_000L;

  private final SwarmLifecycle lifecycle;
  private final SwarmControllerStatusPublisher publisher;
  private final BooleanSupplier initialized;
  private final Clock clock;
  private final AtomicBoolean startupReadyStatusEmitted = new AtomicBoolean(false);
  private final AtomicReference<PendingStatusFull> pendingStatusFull = new AtomicReference<>();

  SwarmStatusFullCoordinator(
      SwarmLifecycle lifecycle,
      SwarmControllerStatusPublisher publisher,
      BooleanSupplier initialized) {
    this(lifecycle, publisher, initialized, Clock.systemUTC());
  }

  SwarmStatusFullCoordinator(
      SwarmLifecycle lifecycle,
      SwarmControllerStatusPublisher publisher,
      BooleanSupplier initialized,
      Clock clock) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.initialized = Objects.requireNonNull(initialized, "initialized");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  void maybePublishStartupReady() {
    boolean ready = initialized.getAsBoolean()
        && lifecycle.isReadyForWork()
        && !lifecycle.hasPendingConfigUpdates();
    if (ready && startupReadyStatusEmitted.compareAndSet(false, true)) {
      publisher.publishFull();
    }
  }

  void queueAfterLifecycle(long freshnessCutoffMillis) {
    pendingStatusFull.set(new PendingStatusFull(freshnessCutoffMillis, clock.millis()));
    maybePublishPending();
  }

  void maybePublishPending() {
    PendingStatusFull pending = pendingStatusFull.get();
    if (pending == null) {
      return;
    }
    boolean fresh = lifecycle.hasFreshWorkerStatusSnapshotsSince(pending.freshnessCutoffMillis());
    boolean timedOut = clock.millis() - pending.queuedAtMillis() >= POST_LIFECYCLE_TIMEOUT_MS;
    if ((fresh || timedOut) && pendingStatusFull.compareAndSet(pending, null)) {
      publisher.publishFull();
    }
  }

  private record PendingStatusFull(long freshnessCutoffMillis, long queuedAtMillis) {
  }
}
