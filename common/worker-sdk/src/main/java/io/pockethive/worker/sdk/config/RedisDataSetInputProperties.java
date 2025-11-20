package io.pockethive.worker.sdk.config;

/**
 * Redis-backed dataset input configuration bound from {@code pockethive.inputs.redis.*}.
 */
public class RedisDataSetInputProperties implements WorkInputConfig {

    private boolean enabled = false;
    private String host;
    private int port = 6379;
    private String username;
    private String password;
    private boolean ssl = false;
    private String listName;
    private double ratePerSec = 1.0;
    private long initialDelayMs = 0L;
    private long tickIntervalMs = 1_000L;

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
        this.port = Math.max(1, port);
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

    public String getListName() {
        return listName;
    }

    public void setListName(String listName) {
        this.listName = normalise(listName);
    }

    public double getRatePerSec() {
        return ratePerSec;
    }

    public void setRatePerSec(double ratePerSec) {
        this.ratePerSec = Math.max(0.0, ratePerSec);
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = Math.max(0L, initialDelayMs);
    }

    public long getTickIntervalMs() {
        return tickIntervalMs;
    }

    public void setTickIntervalMs(long tickIntervalMs) {
        this.tickIntervalMs = Math.max(100L, tickIntervalMs);
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
