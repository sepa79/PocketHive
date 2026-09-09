package io.pockethive.work.config.redis;

/**
 * Responsibility: identify a validated dataset source mode or an unresolved validation result.
 * Must not: infer a mode from raw settings or select a Redis list at runtime.
 * Contract: RESP-WORK-REDIS-SELECTION — docs/architecture/runtime-responsibilities.md#resp-work-redis-selection.
 */
public enum RedisDatasetSourceMode {
    SINGLE, MULTIPLE, UNRESOLVED
}
