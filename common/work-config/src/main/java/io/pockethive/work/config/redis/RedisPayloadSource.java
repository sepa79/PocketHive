package io.pockethive.work.config.redis;

/**
 * Responsibility: name the WorkItem payload selected for a Redis write.
 * Must not: parse settings or read a WorkItem.
 * Contract: RESP-WORK-REDIS-WRITE-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-redis-write-settings.
 */
public enum RedisPayloadSource { FIRST, LAST }
