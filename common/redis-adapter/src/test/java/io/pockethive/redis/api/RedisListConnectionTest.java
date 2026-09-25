package io.pockethive.redis.api;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.pockethive.redis.config.RedisConfigurationParser;
import io.pockethive.redis.config.RedisPushDirection;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisListConnectionTest {
    @Test void usesResolvedConnectionAndPreservesPushThenTrimAndPop() {
        RedisClient client = mock(RedisClient.class);
        StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(client.connect()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);
        when(commands.lpop("queue")).thenReturn("payload", (String) null);
        var settings = new RedisConfigurationParser().parseRedisConnection("redis.example", 6381, "user", "password", true, "redis");
        try (var lists = new RedisListConnection(settings, uri -> {
            assertThat(uri.getHost()).isEqualTo("redis.example");
            assertThat(uri.getPort()).isEqualTo(6381);
            assertThat(uri.getUsername()).isEqualTo("user");
            assertThat(uri.getPassword()).containsExactly("password".toCharArray());
            assertThat(uri.isSsl()).isTrue();
            return client;
        })) {
            lists.push("queue", "payload", RedisPushDirection.RPUSH, 3);
            var order = inOrder(commands);
            order.verify(commands).rpush("queue", "payload");
            order.verify(commands).ltrim("queue", 0, 2);
            lists.push("unbounded", "value", RedisPushDirection.LPUSH, -1);
            verify(commands).lpush("unbounded", "value");
            verify(commands, never()).ltrim(eq("unbounded"), anyLong(), anyLong());
            assertThat(lists.pop("queue")).isEqualTo("payload");
            assertThat(lists.pop("queue")).isNull();
            verify(connection).setTimeout(Duration.ofSeconds(10));
        }
        verify(connection).close();
        verify(client).shutdown();
    }
    @Test void failedCommandPropagatesAndCloseAlwaysReleasesClientOnce() {
        RedisClient client = mock(RedisClient.class);
        StatefulRedisConnection<String, String> connection = mock(StatefulRedisConnection.class);
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(client.connect()).thenReturn(connection);
        when(connection.sync()).thenReturn(commands);
        var settings = new RedisConfigurationParser().parseRedisConnection("localhost", 6379, null, null, false, "redis");
        var lists = new RedisListConnection(settings, uri -> client);
        var failure = new IllegalStateException("unavailable");
        when(commands.lpush("queue", "value")).thenThrow(failure);
        assertThatThrownBy(() -> lists.push("queue", "value", RedisPushDirection.LPUSH, 3)).isSameAs(failure);
        verify(commands, never()).ltrim(anyString(), anyLong(), anyLong());
        verify(client, never()).shutdown();
        doThrow(failure).when(connection).close();
        assertThatThrownBy(lists::close).isSameAs(failure);
        lists.close();
        verify(client).shutdown();
        verify(connection).close();
    }
}
