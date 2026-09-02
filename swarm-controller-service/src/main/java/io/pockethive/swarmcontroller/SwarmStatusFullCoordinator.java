package io.pockethive.swarmcontroller;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Responsibility: Coordinate one-shot startup and post-lifecycle full-status publication triggers.
 * Must not: Build status payloads, mutate lifecycle state, or decide lifecycle command outcomes.
 * Contract: Publish once when startup becomes ready and after lifecycle freshness or the explicit timeout.
 */
final class SwarmStatusFullCoordinator {

  private static final long POST_LIFECYCLE_TIMEOUT_NANOS = Duration.ofSeconds(5).toNanos();

  private final SwarmLifecycle lifecycle;
  private final SwarmControllerStatusPublisher publisher;
  private final BooleanSupplier initialized;
  private final LongSupplier nanoTime;
  private final AtomicBoolean startupReadyStatusEmitted = new AtomicBoolean(false);
  private final AtomicReference<PendingStatusFull> pendingStatusFull = new AtomicReference<>();

  SwarmStatusFullCoordinator(
      SwarmLifecycle lifecycle,
      SwarmControllerStatusPublisher publisher,
      BooleanSupplier initialized) {
    this(lifecycle, publisher, initialized, System::nanoTime);
  }

  SwarmStatusFullCoordinator(
      SwarmLifecycle lifecycle,
      SwarmControllerStatusPublisher publisher,
      BooleanSupplier initialized,
      LongSupplier nanoTime) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.initialized = Objects.requireNonNull(initialized, "initialized");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
  }

  void maybePublishStartupReady() {
    boolean ready = initialized.getAsBoolean()
        && lifecycle.isReadyForWork()
        && !lifecycle.hasPendingConfigUpdates();
    if (ready && startupReadyStatusEmitted.compareAndSet(false, true)) {
      publisher.publishFull();
    }
  }

  void queueAfterLifecycle(long observationRevision) {
    pendingStatusFull.set(new PendingStatusFull(observationRevision, nanoTime.getAsLong()));
    maybePublishPending();
  }

  void maybePublishPending() {
    PendingStatusFull pending = pendingStatusFull.get();
    if (pending == null) {
      return;
    }
    boolean fresh = lifecycle.hasWorkerStatusSnapshotsAfter(pending.observationRevision());
    boolean timedOut = nanoTime.getAsLong() - pending.queuedAtNanos() >= POST_LIFECYCLE_TIMEOUT_NANOS;
    if ((fresh || timedOut) && pendingStatusFull.compareAndSet(pending, null)) {
      publisher.publishFull();
    }
  }

  private record PendingStatusFull(long observationRevision, long queuedAtNanos) {
  }
}
