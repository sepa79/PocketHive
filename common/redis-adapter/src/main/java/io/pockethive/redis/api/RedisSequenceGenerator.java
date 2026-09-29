package io.pockethive.redis.api;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import io.pockethive.redis.config.RedisConnectionSettings;
import io.pockethive.templating.api.SequenceAccess;

/**
 * Responsibility: own explicitly configured Redis sequence increment/reset and client lifetime.
 * Must not: select global settings or duplicate sequence formatting.
 * Contract: RESP-TEMPLATE-SEQUENCE — docs/architecture/runtime-responsibilities.md#resp-template-sequence.
 */
public final class RedisSequenceGenerator implements SequenceAccess, AutoCloseable {
    private static final String KEY_PREFIX = "ph:seq:";
    private final RedisClient client;
    private final ThreadLocal<RedisCommands<String, String>> commands;
    public RedisSequenceGenerator(RedisConnectionSettings settings) {
        client = RedisConnections.client(settings);
        commands = ThreadLocal.withInitial(() -> client.connect().sync());
    }
    public String next(String key, String mode, String format, long startOffset, long maxSequence) {
        var formatter = SequenceFormatter.prepare(mode, format);
        long value = commands.get().incr(KEY_PREFIX + key);
        return formatter.format(value, startOffset, maxSequence);
    }
    public boolean reset(String key) { return commands.get().del(KEY_PREFIX + key) > 0; }
    public void close() { commands.remove(); client.shutdown(); }
}
