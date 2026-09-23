package io.pockethive.worker.sdk.config;

import io.pockethive.redis.api.RedisSequenceGenerator;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisSequenceLifecycleTest {
    @Test
    void closeWaitsForConcurrentOperationsThenRejectsNextResetAndUpdate() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var closeStarted = new CountDownLatch(1);
        var generator = mock(RedisSequenceGenerator.class);
        when(generator.next("key", "NUMERIC", "%02d", 1, 0)).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("next was not released");
            return "00";
        });
        when(generator.reset("other")).thenAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("reset was not released");
            return true;
        });
        var creations = new AtomicInteger();
        var sequences = new RedisSequenceConfiguration(new RedisSequenceProperties(), settings -> {
            creations.incrementAndGet();
            return generator;
        });
        try (var executor = Executors.newFixedThreadPool(3)) {
            try {
                var next = executor.submit(() -> sequences.next("key", "NUMERIC", "%02d", 1, 0));
                var reset = executor.submit(() -> sequences.reset("other"));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var closing = executor.submit(() -> {
                    closeStarted.countDown();
                    sequences.close();
                });
                assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> closing.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                verify(generator, never()).close();
                release.countDown();
                assertThat(next.get(5, TimeUnit.SECONDS)).isEqualTo("00");
                assertThat(reset.get(5, TimeUnit.SECONDS)).isTrue();
                closing.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> sequences.next("key", "NUMERIC", "%02d", 1, 0))
                    .isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> sequences.reset("other")).isInstanceOf(IllegalStateException.class);
                assertThatThrownBy(() -> sequences.configureFromWorkerConfig(Map.of("redis", Map.of("port", 6380))))
                    .isInstanceOf(IllegalStateException.class);
                assertThat(creations.get()).isEqualTo(1);
                sequences.close();
                verify(generator).close();
            } finally {
                release.countDown();
            }
        } finally {
            sequences.close();
        }
    }

    @Test
    void failedCloseIsTerminalAndStillAttemptsEveryGenerator() {
        var first = mock(RedisSequenceGenerator.class);
        var second = mock(RedisSequenceGenerator.class);
        var creations = new AtomicInteger();
        var sequences = new RedisSequenceConfiguration(new RedisSequenceProperties(),
            settings -> creations.getAndIncrement() == 0 ? first : second);
        sequences.reset("key");
        sequences.configureFromWorkerConfig(Map.of("redis", Map.of("port", 6380)));
        sequences.reset("key");
        var failure = new IllegalStateException("close failed");
        doThrow(failure).when(first).close();
        assertThatThrownBy(sequences::close).isSameAs(failure);
        verify(first).close();
        verify(second).close();
        assertThatThrownBy(() -> sequences.reset("key")).isInstanceOf(IllegalStateException.class);
        assertThat(creations.get()).isEqualTo(2);
        sequences.close();
        verify(first).close();
        verify(second).close();
    }
}
