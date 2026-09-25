package io.pockethive.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ProcessorPacerTest {
  @Test
  void reservesAnIntervalForTheFirstRequestAndEveryQueuedRequest() throws Exception {
    List<Long> waits = new ArrayList<>();
    ProcessorPacer pacer = new ProcessorPacer(() -> 1_000_000_000L,
        (millis, nanos) -> waits.add(TimeUnit.MILLISECONDS.toNanos(millis) + nanos));

    assertThat(pacer.await(rate(10))).isEqualTo(100);
    assertThat(pacer.await(rate(10))).isEqualTo(200);
    assertThat(pacer.await(rate(10))).isEqualTo(300);
    assertThat(waits).containsExactly(100_000_000L, 200_000_000L, 300_000_000L);
  }

  @Test
  void threadCountDoesNotReadTheClockWaitOrRequireARate() throws Exception {
    ProcessorPacer pacer = new ProcessorPacer(
        () -> { throw new AssertionError("THREAD_COUNT must not reserve a slot"); },
        (millis, nanos) -> { throw new AssertionError("THREAD_COUNT must not wait"); });

    assertThat(pacer.await(config(ProcessorWorkerConfig.Mode.THREAD_COUNT, null))).isZero();
  }

  @Test
  void rateChangesAndTemporaryThreadCountModePreserveOutstandingReservations() throws Exception {
    List<Long> waits = new ArrayList<>();
    ProcessorPacer pacer = new ProcessorPacer(() -> 1_000_000_000L,
        (millis, nanos) -> waits.add(millis));

    assertThat(pacer.await(rate(10))).isEqualTo(100);
    assertThat(pacer.await(rate(20))).isEqualTo(150);
    assertThat(pacer.await(config(ProcessorWorkerConfig.Mode.THREAD_COUNT, null))).isZero();
    assertThat(pacer.await(rate(5))).isEqualTo(350);
    assertThat(waits).containsExactly(100L, 150L, 350L);
  }

  @Test
  void idleTimeRebasesTheNextReservationWithoutAccumulatingCredit() throws Exception {
    AtomicLong now = new AtomicLong(1_000_000_000L);
    List<Long> waits = new ArrayList<>();
    ProcessorPacer pacer = new ProcessorPacer(now::get, (millis, nanos) -> waits.add(millis));

    assertThat(pacer.await(rate(10))).isEqualTo(100);
    now.set(5_000_000_000L);
    assertThat(pacer.await(rate(10))).isEqualTo(100);
    assertThat(waits).containsExactly(100L, 100L);
  }

  @ParameterizedTest
  @CsvSource({"3, 333, 333333", "2000, 0, 500000"})
  void preservesNanosecondWaitAndTruncatesOnlyReportedMilliseconds(
      double rate, long expectedMillis, int expectedNanos) throws Exception {
    List<Long> millisParts = new ArrayList<>();
    List<Integer> nanosParts = new ArrayList<>();
    ProcessorPacer pacer = new ProcessorPacer(() -> 1_000_000_000L, (millis, nanos) -> {
      millisParts.add(millis);
      nanosParts.add(nanos);
    });

    assertThat(pacer.await(rate(rate))).isEqualTo(expectedMillis);
    assertThat(millisParts).containsExactly(expectedMillis);
    assertThat(nanosParts).containsExactly(expectedNanos);
  }

  @Test
  void aSubNanosecondIntervalRetainsExistingNoWaitBehavior() throws Exception {
    ProcessorPacer pacer = new ProcessorPacer(() -> 1_000_000_000L,
        (millis, nanos) -> { throw new AssertionError("Truncated zero interval must not sleep"); });

    assertThat(pacer.await(rate(2_000_000_000d))).isZero();
  }

  @Test
  void interruptionPropagatesAndDoesNotReleaseTheReservedSlot() throws Exception {
    AtomicBoolean interruptFirst = new AtomicBoolean(true);
    InterruptedException failure = new InterruptedException("test interruption");
    List<Long> waits = new ArrayList<>();
    ProcessorPacer pacer = new ProcessorPacer(() -> 1_000_000_000L, (millis, nanos) -> {
      waits.add(millis);
      if (interruptFirst.getAndSet(false)) {
        throw failure;
      }
    });

    assertThatThrownBy(() -> pacer.await(rate(10))).isSameAs(failure);
    assertThat(pacer.await(rate(10))).isEqualTo(200);
    assertThat(waits).containsExactly(100L, 200L);
  }

  @Test
  void reportsPlannedWaitRatherThanActualOversleep() throws Exception {
    AtomicLong now = new AtomicLong(1_000_000_000L);
    ProcessorPacer pacer = new ProcessorPacer(now::get, (millis, nanos) -> now.addAndGet(500_000_000L));

    assertThat(pacer.await(rate(10))).isEqualTo(100);
    assertThat(pacer.await(rate(10))).isEqualTo(100);
  }

  @Test
  void simultaneousRequestsReserveDistinctSlotsFromOneSchedule() throws Exception {
    ConcurrentLinkedQueue<Long> waits = new ConcurrentLinkedQueue<>();
    ProcessorPacer pacer = new ProcessorPacer(() -> 1_000_000_000L,
        (millis, nanos) -> waits.add(TimeUnit.MILLISECONDS.toNanos(millis) + nanos));
    List<Long> reportedWaits = new ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(8)) {
      List<Future<Long>> results = new ArrayList<>();
      for (int i = 0; i < 32; i++) {
        results.add(executor.submit(() -> pacer.await(rate(100))));
      }
      for (Future<Long> result : results) {
        reportedWaits.add(result.get(5, TimeUnit.SECONDS));
      }
    }

    assertThat(reportedWaits.stream().sorted().toList())
        .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 32).map(i -> i * 10).boxed().toList());
    assertThat(waits.stream().sorted().toList())
        .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 32).map(i -> i * 10_000_000).boxed().toList());
  }

  @Test
  void separateWorkerSchedulesDoNotConsumeEachOthersSlots() throws Exception {
    ProcessorPacer first = new ProcessorPacer(() -> 1_000_000_000L, (millis, nanos) -> {});
    ProcessorPacer second = new ProcessorPacer(() -> 1_000_000_000L, (millis, nanos) -> {});

    assertThat(first.await(rate(10))).isEqualTo(100);
    assertThat(first.await(rate(10))).isEqualTo(200);
    assertThat(second.await(rate(10))).isEqualTo(100);
  }

  private static ProcessorWorkerConfig rate(double rate) {
    return config(ProcessorWorkerConfig.Mode.RATE_PER_SEC, rate);
  }

  private static ProcessorWorkerConfig config(ProcessorWorkerConfig.Mode mode, Double rate) {
    return new ProcessorWorkerConfig("http://pacing.invalid", mode, 8, rate,
        ProcessorWorkerConfig.ConnectionReuse.GLOBAL, true, 5000, true, null);
  }
}
