package io.pockethive.processor;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Responsibility: reserve and wait for the shared per-worker processor request schedule.
 * Must not: validate configuration, execute protocols or decide result/acknowledgement policy.
 * Contract: RESP-PROCESSOR-PACING — docs/architecture/runtime-responsibilities.md#resp-processor-pacing.
 */
public final class ProcessorPacer {
  private final AtomicLong nextAllowedTimeNanos = new AtomicLong(0L);
  private final LongSupplier nanoTime;
  private final ProcessorPacingSleeper sleeper;

  public ProcessorPacer() {
    this(System::nanoTime, Thread::sleep);
  }

  ProcessorPacer(LongSupplier nanoTime, ProcessorPacingSleeper sleeper) {
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  /** Returns the planned wait in whole milliseconds, preserving the existing metrics semantics. */
  public long await(ProcessorWorkerConfig config) throws InterruptedException {
    if (config.mode() == ProcessorWorkerConfig.Mode.RATE_PER_SEC) {
      double rate = config.ratePerSec();
      if (rate <= 0.0) {
        return 0L;
      }
      long intervalNanos = (long) (1_000_000_000L / rate);
      long now = nanoTime.getAsLong();
      long prev = nextAllowedTimeNanos.getAndUpdate(current -> {
        long base = Math.max(current, now);
        return base + intervalNanos;
      });
      long base = Math.max(prev, now);
      long scheduled = base + intervalNanos;
      long sleepNanos = scheduled - now;
      if (sleepNanos > 0L) {
        long millis = sleepNanos / 1_000_000L;
        int nanos = (int) (sleepNanos % 1_000_000L);
        sleeper.sleep(millis, nanos);
        return sleepNanos / 1_000_000L;
      }
    }
    return 0L;
  }
}
