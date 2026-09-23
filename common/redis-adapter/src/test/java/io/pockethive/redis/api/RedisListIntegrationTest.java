package io.pockethive.redis.api;

import io.pockethive.redis.config.RedisConfigurationParser;
import io.pockethive.redis.config.RedisPushDirection;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RedisListIntegrationTest {
    @Test
    void producerAndConsumerPreserveOrderAndBoundedListContents() {
        String host = System.getenv("AUTH_REDIS_TEST_HOST");
        assumeTrue(host != null, "explicit Redis fixture required");
        var settings = new RedisConfigurationParser().parseRedisConnection(host,
            Integer.parseInt(System.getenv("AUTH_REDIS_TEST_PORT")), null, null, false, "redis");
        String list = "extraction-" + UUID.randomUUID();
        try (var writer = RedisListClients.writer(settings); var reader = RedisListClients.reader(settings)) {
            writer.push(list, "a", RedisPushDirection.RPUSH, -1);
            writer.push(list, "b", RedisPushDirection.RPUSH, -1);
            writer.push(list, "c", RedisPushDirection.LPUSH, 2);
            assertThat(reader.pop(list)).isEqualTo("c");
            assertThat(reader.pop(list)).isEqualTo("a");
            assertThat(reader.pop(list)).isNull();
        }
    }
}
