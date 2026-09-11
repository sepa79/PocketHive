package io.pockethive.redis.config;

/**
 * Responsibility: name the configured Redis list insertion direction.
 * Must not: parse settings or execute Redis commands.
 * Contract: RESP-WORK-REDIS-WRITE-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-redis-write-settings.
 */
public enum RedisPushDirection { LPUSH, RPUSH }
