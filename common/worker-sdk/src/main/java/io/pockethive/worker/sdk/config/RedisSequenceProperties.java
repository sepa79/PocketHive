package io.pockethive.worker.sdk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis sequence generator configuration bound from {@code pockethive.worker.config.redis.*}.
 */
@ConfigurationProperties(prefix = "pockethive.worker.config.redis")
public class RedisSequenceProperties {

    private boolean enabled = true;
    private String host = "redis";
    private int port = 6379;
    private String username;
    private String password;
    private boolean ssl = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = normalise(host);
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = Math.max(1, Math.min(65535, port));
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = normalise(username);
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = normalise(password);
    }

    public boolean isSsl() {
        return ssl;
    }

    public void setSsl(boolean ssl) {
        this.ssl = ssl;
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
