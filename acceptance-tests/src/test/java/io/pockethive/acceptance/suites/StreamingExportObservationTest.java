package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.exports.ExportFilesSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import io.pockethive.acceptance.evidence.RunEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StreamingExportObservationTest {
  @TempDir Path reports;
  private RunEvidence evidence;
  @BeforeEach void openEvidence() throws IOException { evidence = new RunEvidence(reports, "streaming"); }
  @AfterEach void closeEvidence() throws IOException { evidence.close(); }

  private final ExportFilesSnapshot complete = new ExportFilesSnapshot(Map.of("output.txt", "complete"), Set.of());
  private final Duration window = Duration.ofSeconds(15);
  private final Duration flush = Duration.ofMinutes(15);

  @Test void lateStartConfirmationCannotHideEarlyFinalization() throws Exception {
    var samples = observeWithDelayedConfirmation(5);
    assertEquals(5000L, samples.getLast().elapsedSinceStartRequestMs());
    assertThrows(AssertionError.class, () -> StreamingExportObservation.requireWindow(samples, window, flush));
  }

  @Test void acceptsWindowFinalizationBeforeStartConfirmationReturns() throws Exception {
    var samples = observeWithDelayedConfirmation(15);
    assertEquals(15000L, samples.getLast().elapsedSinceStartRequestMs());
    StreamingExportObservation.requireWindow(samples, window, flush);
  }

  private List<TimedExportFiles> observeWithDelayedConfirmation(long finalizedSeconds) throws Exception {
    var startEntered = new CountDownLatch(1);
    var timestampCaptured = new CountDownLatch(1);
    var now = new AtomicLong();
    var ticks = new AtomicInteger();
    return StreamingExportObservation.duringStart(() -> {
      startEntered.countDown();
      assertTrue(timestampCaptured.await(2, TimeUnit.SECONDS), "Observation must run while START waits");
      now.set(Duration.ofSeconds(20).toNanos());
      return null;
    }, () -> {
      assertTrue(startEntered.await(2, TimeUnit.SECONDS));
      now.set(Duration.ofSeconds(finalizedSeconds).toNanos());
      return complete;
    }, Duration.ofSeconds(2), Duration.ofMillis(1), evidence, () -> {
      long captured = now.get();
      if (ticks.incrementAndGet() > 1) timestampCaptured.countDown();
      return captured;
    });
  }

  @Test void earlyFinalFileCannotBeHiddenByPendingFilesUntilLater() {
    var samples = List.of(new TimedExportFiles(5000,
        new ExportFilesSnapshot(complete.finalized(), Set.of("other.tmp"))), new TimedExportFiles(20000, complete));
    assertThrows(AssertionError.class, () -> StreamingExportObservation.requireWindow(samples, window, flush));
  }

  @Test void failedStartCancelsAndJoinsObserverBeforeReturning() throws Exception {
    var reading = new CountDownLatch(1);
    var reads = new AtomicInteger();
    var exited = new AtomicBoolean();
    var failure = new IOException("START failed");
    var actual = assertThrows(IOException.class, () -> StreamingExportObservation.duringStart(() -> {
      assertTrue(reading.await(2, TimeUnit.SECONDS));
      throw failure;
    }, () -> {
      if (reads.incrementAndGet() == 1) return new ExportFilesSnapshot(Map.of(), Set.of("before-start-failure.tmp"));
      reading.countDown();
      try {
        new CountDownLatch(1).await();
        return complete;
      } finally {
        exited.set(true);
      }
    }, Duration.ofSeconds(2), Duration.ofMillis(1), evidence));
    assertSame(failure, actual);
    assertTrue(exited.get(), "Observer must finish before caller starts resource cleanup");
    assertEquals(1, savedSamples().size());
  }
  @Test void timeoutRetainsPendingSamplesBeforeCallerCleanup() throws Exception {
    var pending = new ExportFilesSnapshot(Map.of(), Set.of("unfinished.txt.tmp"));
    var failure = assertThrows(ExecutionException.class, () -> StreamingExportObservation.duringStart(
        () -> null, () -> pending, Duration.ofMillis(50), Duration.ofMillis(2), evidence));
    assertInstanceOf(AssertionError.class, failure.getCause());
    assertTrue(failure.getCause().getMessage().contains("timed out"));
    var saved = savedSamples();
    assertTrue(saved.size() > 0);
    assertEquals("unfinished.txt.tmp", saved.get(0).required("files").required("pending").get(0).textValue());
  }

  @Test void readFailureRetainsEarlierSamplesAndOriginalCause() throws Exception {
    var reads = new AtomicInteger();
    var failure = new IOException("file read failed");
    var actual = assertThrows(ExecutionException.class, () -> StreamingExportObservation.duringStart(
        () -> null, () -> {
          if (reads.incrementAndGet() == 1) return new ExportFilesSnapshot(Map.of(), Set.of("first.txt.tmp"));
          throw failure;
        }, Duration.ofSeconds(2), Duration.ofMillis(1), evidence));
    assertSame(failure, actual.getCause());
    var saved = savedSamples();
    assertEquals(1, saved.size());
    assertEquals("first.txt.tmp", saved.get(0).required("files").required("pending").get(0).textValue());
  }

  @Test void evidenceWriteFailureIsSuppressedWithoutReplacingReadFailure() throws Exception {
    Files.createDirectory(evidence.directory().resolve("streaming-observations.json"));
    var readFailure = new IOException("original read failure");
    var failure = assertThrows(ExecutionException.class, () -> {
      try (var ownedEvidence = evidence) {
        StreamingExportObservation.duringStart(() -> null, () -> { throw readFailure; },
            Duration.ofSeconds(2), Duration.ofMillis(1), ownedEvidence);
      }
    });
    assertSame(readFailure, failure.getCause());
    assertEquals(1, failure.getSuppressed().length);
    assertInstanceOf(IOException.class, failure.getSuppressed()[0]);
  }

  @Test void interruptedCallerStillJoinsObserverAndSavesSamples() throws Exception {
    var reading = new CountDownLatch(1);
    var reads = new AtomicInteger();
    var exited = new AtomicBoolean();
    var interrupted = new InterruptedException("START interrupted");
    try {
      var actual = assertThrows(InterruptedException.class, () -> StreamingExportObservation.duringStart(() -> {
        assertTrue(reading.await(2, TimeUnit.SECONDS));
        Thread.currentThread().interrupt();
        throw interrupted;
      }, () -> {
        if (reads.incrementAndGet() == 1) return new ExportFilesSnapshot(Map.of(), Set.of("before-interruption.tmp"));
        reading.countDown();
        try {
          new CountDownLatch(1).await();
          return complete;
        } finally { exited.set(true); }
      }, Duration.ofSeconds(2), Duration.ofMillis(1), evidence));
      assertSame(interrupted, actual);
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertTrue(exited.get());
    assertEquals(1, savedSamples().size());
  }

  private com.fasterxml.jackson.databind.JsonNode savedSamples() throws IOException {
    return new ObjectMapper().readTree(evidence.directory().resolve("streaming-observations.json").toFile());
  }

}
