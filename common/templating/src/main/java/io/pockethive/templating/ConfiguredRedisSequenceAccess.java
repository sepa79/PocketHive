package io.pockethive.templating;

import io.pockethive.templating.api.SequenceAccess;

/**
 * Responsibility: expose the existing configured Redis sequence owner through its port.
 * Must not: resolve settings, cache a second generator, or implement sequence algorithms.
 * Contract: RESP-TEMPLATE-SEQUENCE — docs/architecture/runtime-responsibilities.md#resp-template-sequence.
 */
public final class ConfiguredRedisSequenceAccess implements SequenceAccess {
    @Override
    public String next(String key, String mode, String format, long startOffset, long maxSequence) {
        return RedisSequenceGenerator.getDefaultInstance().next(key, mode, format, startOffset, maxSequence);
    }
    @Override
    public boolean reset(String key) { return RedisSequenceGenerator.getDefaultInstance().reset(key); }
}
