package io.pockethive.worker.sdk.input.message;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import io.pockethive.work.api.transport.WorkNotAcceptedException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MessageWorkExecutorTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 3})
    void admissionReturnsBeforeCompletionAndPauseRejectsOnlyCapacityWaiters(int limit) throws Exception {
        var release = new CountDownLatch(1);
        var entered = new CountDownLatch(limit);
        var finished = new CountDownLatch(limit);
        var next = new AtomicInteger();
        try (var executor = new MessageWorkExecutor("test")) {
            executor.setMaxInFlight(limit);
            executor.resume();
            for (int i = 0; i < limit; i++) executor.execute(() -> {
                entered.countDown();
                try { awaitLatch(release); } finally { finished.countDown(); }
            });
            try (var callbacks = Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                    var waiting = callbacks.submit(() -> executor.execute(next::incrementAndGet));
                    await().during(Duration.ofMillis(150)).atMost(Duration.ofSeconds(2))
                        .untilAsserted(() -> assertThat(waiting).isNotDone());
                    executor.pause();
                    assertThatThrownBy(() -> waiting.get(2, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(WorkNotAcceptedException.class);
                    assertThat(next).hasValue(0);
                    assertThat(finished.getCount()).isEqualTo(limit);
                    release.countDown();
                    assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
                    executor.resume();
                    executor.execute(next::incrementAndGet);
                    await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(next).hasValue(1));
                } finally { release.countDown(); executor.pause(); }
            }
        }
    }

    @Test
    void raisingAndLoweringTheLimitDoesNotCreateAnInlinePathOrExceedTheNewLimit() throws Exception {
        var firstRelease = new CountDownLatch(1);
        var secondRelease = new CountDownLatch(1);
        var firstDone = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var thirdEntered = new CountDownLatch(1);
        try (var executor = new MessageWorkExecutor("resize");
             var callbacks = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                executor.resume();
                executor.execute(() -> { awaitLatch(firstRelease); firstDone.countDown(); });
                var second = callbacks.submit(() -> executor.execute(() -> {
                    secondEntered.countDown(); awaitLatch(secondRelease);
                }));
                executor.setMaxInFlight(2);
                second.get(2, TimeUnit.SECONDS);
                assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
                executor.setMaxInFlight(1);
                var third = callbacks.submit(() -> executor.execute(thirdEntered::countDown));
                firstRelease.countDown();
                assertThat(firstDone.await(2, TimeUnit.SECONDS)).isTrue();
                await().during(Duration.ofMillis(150)).atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(third).isNotDone());
                secondRelease.countDown();
                third.get(2, TimeUnit.SECONDS);
                assertThat(thirdEntered.await(2, TimeUnit.SECONDS)).isTrue();
            } finally { firstRelease.countDown(); secondRelease.countDown(); executor.pause(); }
        }
    }

    @Test
    void closeRejectsFurtherAdmissionWithoutWaitingForOrInterruptingAcceptedWork() throws Exception {
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var interrupted = new AtomicInteger();
        try (var executor = new MessageWorkExecutor("close")) {
            try {
                executor.resume();
                executor.execute(() -> {
                    try { release.await(); }
                    catch (InterruptedException failure) { interrupted.incrementAndGet(); }
                    finally { finished.countDown(); }
                });
                executor.close();
                assertThat(finished.getCount()).isEqualTo(1);
                assertThatThrownBy(() -> executor.execute(() -> fail("must not execute")))
                    .isInstanceOf(WorkNotAcceptedException.class);
                assertThatThrownBy(executor::resume).isInstanceOf(IllegalStateException.class);
            } finally { release.countDown(); }
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(interrupted).hasValue(0);
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(70)
    void idleThreadsRetainPerThreadResourcesBeyondTheFormerCachedPoolExpiry() throws Exception {
        var initialized = new AtomicInteger();
        var resources = ThreadLocal.withInitial(initialized::incrementAndGet);
        var before = new java.util.concurrent.ConcurrentHashMap<Long, Integer>();
        var after = new java.util.concurrent.ConcurrentHashMap<Long, Integer>();
        try (var executor = new MessageWorkExecutor("per-thread")) {
            executor.setMaxInFlight(2);
            executor.resume();
            runOnBothThreads(executor, resources, before);
            executor.pause();
            // Real elapsed time exercises the previous 60-second expiry without inspecting
            // executor internals or adding production timing knobs solely for this test.
            TimeUnit.SECONDS.sleep(61);
            executor.resume();
            runOnBothThreads(executor, resources, after);
            assertThat(initialized).hasValue(2);
            assertThat(after).containsExactlyInAnyOrderEntriesOf(before);
        }
    }

    private static void runOnBothThreads(MessageWorkExecutor executor, ThreadLocal<Integer> resource,
                                        java.util.Map<Long, Integer> observed) throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(2);
        try {
            for (int i = 0; i < 2; i++) executor.execute(() -> {
                try {
                    observed.put(Thread.currentThread().threadId(), resource.get());
                    entered.countDown();
                    awaitLatch(release);
                } finally { finished.countDown(); }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); }
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test task timed out");
        } catch (InterruptedException failure) { throw new AssertionError(failure); }
    }
}
