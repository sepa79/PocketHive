package io.pockethive.worker.sdk.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RedisDebugCaptureStoreTest {
    private final RedisClient client = mock(RedisClient.class);
    @SuppressWarnings("unchecked")
    private final StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
    @SuppressWarnings("unchecked")
    private final RedisCommands<String, String> commands = mock(RedisCommands.class);
    @SuppressWarnings("unchecked")
    private final Function<RedisURI, RedisClient> factory = mock(Function.class);

    private RedisDebugCaptureStore store() {
        when(factory.apply(any())).thenReturn(client);
        when(client.connect()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);
        RedisSequenceProperties properties = new RedisSequenceProperties();
        properties.setHost("127.0.0.1");
        properties.setPort(6379);
        return new RedisDebugCaptureStore(properties.connectionSettings(RedisSequenceProperties.PREFIX), factory);
    }

    @Test
    void unusedAndClosedStoreNeverAllocate() {
        RedisDebugCaptureStore store = store();
        verifyNoInteractions(factory);
        store.close();
        store.close();
        assertThat(store.store("capture", 120, "{}" )).isFalse();
        verifyNoInteractions(factory, client, connection, commands);
    }

    @Test
    void manyWriterThreadsReuseOneOwnedConnectionAndRetainTtl() throws Exception {
        try (RedisDebugCaptureStore store = store(); var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 40; i++) {
                int index = i;
                tasks.add(pool.submit(() -> store.store("capture-" + index, 17, "value-" + index)));
            }
            for (var task : tasks) {
                assertThat(task.get(5, TimeUnit.SECONDS)).isTrue();
            }
            verify(factory, times(1)).apply(any());
            verify(client, times(1)).connect();
            for (int i = 0; i < 40; i++) {
                verify(commands).setex("capture-" + i, 17, "value-" + i);
            }
        }
        var order = inOrder(connection, client);
        order.verify(connection).close();
        order.verify(client).shutdown();
    }

    @Test
    void failedConnectionReleasesClientAndLaterWriteCanRetry() {
        RedisDebugCaptureStore store = store();
        when(client.connect()).thenThrow(new IllegalStateException("unavailable")).thenReturn(connection);
        assertThat(store.store("one", 17, "{}")).isFalse();
        verify(client).shutdown();
        assertThat(store.store("two", 17, "{}")).isTrue();
        store.close();
        verify(client, times(2)).shutdown();
        verify(connection).close();
        verify(commands).setex("two", 17, "{}");
        verify(commands, never()).setex("one", 17, "{}");
    }

    @Test
    void failedClientCreationRemainsBestEffortAndCanRetry() {
        try (RedisDebugCaptureStore store = store()) {
            when(factory.apply(any())).thenThrow(new IllegalStateException("allocation failed")).thenReturn(client);
            assertThat(store.store("one", 17, "{}")).isFalse();
            assertThat(store.store("two", 17, "{}")).isTrue();
            verify(client, times(1)).connect();
        }
        verify(connection).close();
        verify(client).shutdown();
    }

    @Test
    void commandFailureIsBestEffortWithoutAllocatingAnotherConnection() {
        try (RedisDebugCaptureStore store = store()) {
            when(commands.setex(anyString(), anyLong(), anyString()))
                .thenThrow(new IllegalStateException("write failed")).thenReturn("OK");
            assertThat(store.store("one", 17, "{}")).isFalse();
            assertThat(store.store("two", 17, "{}")).isTrue();
            verify(client, times(1)).connect();
        }
        verify(connection).close();
        verify(client).shutdown();
    }

    @Test
    void commandAccessFailureStillReleasesConnectionAtClose() {
        RedisDebugCaptureStore store = store();
        when(connection.sync()).thenThrow(new IllegalStateException("sync failed"));
        assertThat(store.store("capture", 17, "{}")).isFalse();
        store.close();
        verify(connection).close();
        verify(client).shutdown();
    }

    @Test
    void closeIsIdempotentAndCannotReopen() {
        RedisDebugCaptureStore store = store();
        assertThat(store.store("capture", 17, "{}")).isTrue();
        store.close();
        store.close();
        assertThat(store.store("later", 17, "{}")).isFalse();
        verify(connection).close();
        verify(client).shutdown();
        verify(client).connect();
        verify(commands, never()).setex("later", 17, "{}");
    }

    @Test
    void closeAttemptsBothResourcesAndPreservesBothFailures() {
        RedisDebugCaptureStore store = store();
        assertThat(store.store("capture", 17, "{}")).isTrue();
        RuntimeException connectionFailure = new IllegalStateException("connection close");
        RuntimeException clientFailure = new IllegalArgumentException("client close");
        doThrow(connectionFailure).when(connection).close();
        doThrow(clientFailure).when(client).shutdown();
        assertThatThrownBy(store::close).isSameAs(connectionFailure)
            .hasSuppressedException(clientFailure);
        store.close();
        verify(connection).close();
        verify(client).shutdown();
        assertThat(store.store("later", 17, "{}")).isFalse();
    }

    @Test
    void connectionErrorStillAttemptsClientShutdown() {
        RedisDebugCaptureStore store = store();
        assertThat(store.store("capture", 17, "{}")).isTrue();
        Error failure = new AssertionError("connection cleanup");
        doThrow(failure).when(connection).close();
        assertThatThrownBy(store::close).isSameAs(failure);
        verify(client).shutdown();
    }

    @Test
    void clientCleanupFailureIsReported() {
        RedisDebugCaptureStore store = store();
        assertThat(store.store("capture", 17, "{}")).isTrue();
        RuntimeException failure = new IllegalStateException("client cleanup");
        doThrow(failure).when(client).shutdown();
        assertThatThrownBy(store::close).isSameAs(failure);
        verify(connection).close();
    }

    @Test
    void connectionCreationErrorPreservesCleanupFailure() {
        RedisDebugCaptureStore store = store();
        Error failure = new AssertionError("connect error");
        RuntimeException cleanup = new IllegalStateException("client cleanup");
        when(client.connect()).thenThrow(failure);
        doThrow(cleanup).when(client).shutdown();
        assertThatThrownBy(() -> store.store("capture", 17, "{}"))
            .isSameAs(failure).hasSuppressedException(cleanup);
        store.close();
        verify(client).shutdown();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cleanupPreservesInterruptionButAllowsClientShutdown(boolean interruptedByConnection) {
        RedisDebugCaptureStore store = store();
        assertThat(store.store("capture", 17, "{}")).isTrue();
        doAnswer(invocation -> {
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            if (interruptedByConnection) {
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(connection).close();
        doAnswer(invocation -> {
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            return null;
        }).when(client).shutdown();
        if (!interruptedByConnection) {
            Thread.currentThread().interrupt();
        }
        try {
            store.close();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(client).shutdown();
    }

    @Test
    void closeWaitsForInFlightWriteAndPreventsLaterWrites() throws Exception {
        RedisDebugCaptureStore store = store();
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch allowWrite = new CountDownLatch(1);
        CountDownLatch closeEntered = new CountDownLatch(1);
        when(commands.setex(anyString(), anyLong(), anyString())).thenAnswer(invocation -> {
            writeEntered.countDown();
            assertThat(allowWrite.await(5, TimeUnit.SECONDS)).isTrue();
            return "OK";
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            try {
                var writer = pool.submit(() -> store.store("capture", 17, "{}"));
                assertThat(writeEntered.await(5, TimeUnit.SECONDS)).isTrue();
                var closer = pool.submit(() -> { closeEntered.countDown(); store.close(); });
                assertThat(closeEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> closer.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                verify(connection, never()).close();
                allowWrite.countDown();
                assertThat(writer.get(5, TimeUnit.SECONDS)).isTrue();
                closer.get(5, TimeUnit.SECONDS);
                assertThat(store.store("later", 17, "{}")).isFalse();
            } finally {
                allowWrite.countDown();
            }
        } finally {
            store.close();
        }
        verify(connection).close();
        verify(client).shutdown();
    }
}
