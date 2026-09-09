package io.pockethive.worker.sdk.config;

import io.pockethive.work.config.redis.RedisConnectionSettings;
import io.pockethive.work.config.redis.RedisConfigurationParser;

/**
 * Responsibility: bind raw Redis connection fields and delegate resolution to work-config.
 * Must not: normalize, validate or default connection values or open a client.
 * Contract: RESP-REDIS-CONNECTION-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-redis-connection-settings.
 */
public class RedisConnectionProperties {
    private Object host;
    private Object port;
    private Object username;
    private Object password;
    private Object ssl;

    public Object getHost() { return host; }
    public void setHost(Object host) { this.host = host; }
    public Object getPort() { return port; }
    public void setPort(Object port) { this.port = port; }
    public Object getUsername() { return username; }
    public void setUsername(Object username) { this.username = username; }
    public Object getPassword() { return password; }
    public void setPassword(Object password) { this.password = password; }
    public Object getSsl() { return ssl; }
    public void setSsl(Object ssl) { this.ssl = ssl; }

    public RedisConnectionSettings connectionSettings(String path) {
        return new RedisConfigurationParser().parseRedisConnection(host, port, username, password, ssl, path);
    }

    public void applyConnection(RedisConnectionSettings settings) {
        host = settings.host();
        port = settings.port();
        username = settings.username();
        password = settings.password();
        ssl = settings.ssl();
    }
}
