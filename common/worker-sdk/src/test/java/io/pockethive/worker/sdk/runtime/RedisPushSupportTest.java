package io.pockethive.worker.sdk.runtime;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import io.pockethive.redis.api.RedisListWriter;
import io.pockethive.redis.config.RedisConfigurationParser;
import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class RedisPushSupportTest {
    @Test
    void shutdownAttemptsEveryCachedWriterEvenWhenOneCloseFails() {
        var parser = new RedisConfigurationParser();
        var first = parser.parseRedisConnection("first", 6379, null, null, false, "redis");
        var second = parser.parseRedisConnection("second", 6379, null, null, false, "redis");
        var a = mock(RedisListWriter.class);
        var b = mock(RedisListWriter.class);
        var writers = Map.of(first, a, second, b);
        var support = new RedisPushSupport(writers::get, mock(TemplateRenderer.class));
        var item = WorkItem.text(new WorkerInfo("processor", "swarm", "instance", "in", "out"), "payload").build();
        var settings = parser.parseRedisWriteSettings("LAST", "RPUSH", -1, "redis");
        support.push(new RedisPushRequest(first, settings, List.of(), "queue", null), item);
        support.push(new RedisPushRequest(second, settings, List.of(), "queue", null), item);
        var failure = new IllegalStateException("close failed");
        doThrow(failure).when(a).close();
        assertThatThrownBy(support::close).isSameAs(failure);
        support.close();
        verify(a).close();
        verify(b).close();
    }

    @Test
    void closeWaitsForConcurrentPushesAndNeverReopensWriter() throws Exception {
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var closeStarted = new CountDownLatch(1);
        var writer = mock(RedisListWriter.class);
        var creations = new AtomicInteger();
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("push was not released");
            }
            return null;
        }).when(writer).push(anyString(), anyString(), any(), anyInt());
        var support = new RedisPushSupport(settings -> {
            creations.incrementAndGet();
            return writer;
        }, (template, context) -> template);
        var parser = new RedisConfigurationParser();
        var request = new RedisPushRequest(
            parser.parseRedisConnection("unused", 6379, null, null, false, "redis"),
            parser.parseRedisWriteSettings("LAST", "RPUSH", -1, "redis"), List.of(), "queue", null);
        var item = WorkItem.text(new WorkerInfo("processor", "swarm", "instance", "in", "out"), "payload").build();
        try (var executor = Executors.newFixedThreadPool(3)) {
            try {
                var first = executor.submit(() -> support.push(request, item));
                var second = executor.submit(() -> support.push(request, item));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var closing = executor.submit(() -> {
                    closeStarted.countDown();
                    support.close();
                });
                assertThat(closeStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> closing.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
                verify(writer, never()).close();
                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
                assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
                closing.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> support.push(request, item)).isInstanceOf(IllegalStateException.class);
                assertThat(creations.get()).isEqualTo(1);
                support.close();
                verify(writer).close();
            } finally {
                release.countDown();
            }
        } finally {
            support.close();
        }
    }
}
