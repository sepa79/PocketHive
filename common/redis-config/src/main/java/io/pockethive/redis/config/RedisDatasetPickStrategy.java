package io.pockethive.redis.config;

/**
 * Responsibility: name the supported Redis dataset selection strategies.
 * Must not: select a source or access Redis.
 * Contract: RESP-WORK-REDIS-SOURCES — docs/architecture/runtime-responsibilities.md#resp-work-redis-sources.
 */
public enum RedisDatasetPickStrategy {
    ROUND_ROBIN,
    WEIGHTED_RANDOM
}
