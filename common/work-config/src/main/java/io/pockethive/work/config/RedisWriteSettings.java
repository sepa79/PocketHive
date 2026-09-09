package io.pockethive.work.config;

/**
 * Responsibility: retain immutable write settings produced by WorkConfigurationParser.
 * Must not: accept raw declarations, select payloads or access Redis.
 * Contract: RESP-WORK-REDIS-WRITE-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-redis-write-settings.
 */
public final class RedisWriteSettings {
    public static final int MIN_MAX_LEN = -1;
    private final RedisPayloadSource sourceStep;
    private final RedisPushDirection pushDirection;
    private final int maxLen;

    RedisWriteSettings(RedisPayloadSource sourceStep, RedisPushDirection pushDirection, int maxLen) {
        this.sourceStep = sourceStep;
        this.pushDirection = pushDirection;
        this.maxLen = maxLen;
    }

    public RedisPayloadSource sourceStep() { return sourceStep; }
    public RedisPushDirection pushDirection() { return pushDirection; }
    public int maxLen() { return maxLen; }
}
