package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.assertTrue;
import io.pockethive.acceptance.exports.ExportFilesSnapshot;
import io.pockethive.acceptance.evidence.RunEvidence;
import io.pockethive.acceptance.operations.Deadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;

/**
 * Responsibility: collect streaming observations during START and retain them on every exit.
 * Must not: issue lifecycle requests, write evidence concurrently or resolve output paths.
 * Contract: RESP-ACCEPTANCE-EXPORT-FILES — docs/architecture/acceptance-tests.md#resp-acceptance-export-files.
 */
final class StreamingExportObservation {
  private StreamingExportObservation() { }

  static List<TimedExportFiles> duringStart(Callable<?> start, Callable<ExportFilesSnapshot> read,
      Duration budget, Duration poll, RunEvidence evidence) throws Exception {
    return duringStart(start, read, budget, poll, evidence, System::nanoTime);
  }

  static List<TimedExportFiles> duringStart(Callable<?> start, Callable<ExportFilesSnapshot> read,
      Duration budget, Duration poll, RunEvidence evidence, LongSupplier clock) throws Exception {
    long started = clock.getAsLong();
    var deadline = new Deadline(budget, "Streaming export finalization");
    var samples = new ArrayList<TimedExportFiles>();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var ready = new CountDownLatch(1);
      var observation = executor.submit(() -> {
        ready.countDown();
        while (true) {
          var files = read.call();
          long elapsed = Duration.ofNanos(clock.getAsLong() - started).toMillis();
          samples.add(new TimedExportFiles(elapsed, files));
          if (!files.finalized().isEmpty() && files.pending().isEmpty()) return List.copyOf(samples);
          deadline.pause(poll);
        }
      });
      try {
        ready.await();
        start.call();
        return observation.get();
      } finally {
        observation.cancel(true);
      }
    } finally {
      // Executor.close has joined the sole writer. Save on the caller thread before resource cleanup.
      evidence.record("streaming-observations", List.copyOf(samples));
    }
  }

  static void requireWindow(List<TimedExportFiles> samples, Duration window, Duration ordinaryFlush) {
    assertTrue(!samples.isEmpty(), "Missing streaming observations");
    for (var sample : samples) {
      if (!sample.files().finalized().isEmpty()) {
        assertTrue(sample.elapsedSinceStartRequestMs() >= window.toMillis(),
            "File observed finalized before streaming window: " + sample.elapsedSinceStartRequestMs() + "ms");
      }
    }
    assertTrue(samples.getLast().elapsedSinceStartRequestMs() < ordinaryFlush.toMillis(),
        "Ordinary flush interval must not explain finalization");
  }
}
