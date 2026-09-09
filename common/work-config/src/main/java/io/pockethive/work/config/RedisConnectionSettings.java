package io.pockethive.work.config;

import java.util.Objects;

/**
 * Responsibility: retain the immutable Redis connection validated by WorkConfigurationParser.
 * Must not: parse raw settings, open clients or expose credentials in diagnostics.
 * Contract: RESP-REDIS-CONNECTION-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-redis-connection-settings.
 */
public final class RedisConnectionSettings {
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final boolean ssl;

    RedisConnectionSettings(String host, int port, String username, String password, boolean ssl) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.ssl = ssl;
    }

    public String host() { return host; }
    public int port() { return port; }
    public String username() { return username; }
    public String password() { return password; }
    public boolean ssl() { return ssl; }

    @Override
    public boolean equals(Object other) {
        return other instanceof RedisConnectionSettings that && port == that.port && ssl == that.ssl
            && host.equals(that.host) && Objects.equals(username, that.username) && Objects.equals(password, that.password);
    }

    @Override
    public int hashCode() { return Objects.hash(host, port, username, password, ssl); }

    @Override
    public String toString() {
        return "RedisConnectionSettings[host=" + host + ", port=" + port + ", ssl=" + ssl + ", credentials=redacted]";
    }
}
