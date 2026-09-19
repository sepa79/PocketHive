package io.pockethive.worker.sdk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Responsibility: bind the existing worker Redis scope and delegate connection validation.
 * Must not: clamp invalid ports, normalize credentials or open clients.
 * Contract: RESP-REDIS-CONNECTION-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-redis-connection-settings.
 * Existing bootstrap defaults and token/sequence scope composition remain B02 debt.
 */
@ConfigurationProperties(prefix = RedisSequenceProperties.PREFIX)
public class RedisSequenceProperties extends RedisConnectionProperties {
    public static final String PREFIX = "pockethive.worker.config.redis";
    private boolean enabled = true;

    public RedisSequenceProperties() {
        setHost("redis");
        setPort(6379);
        setSsl(false);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
