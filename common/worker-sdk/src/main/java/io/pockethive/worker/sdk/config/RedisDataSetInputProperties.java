package io.pockethive.worker.sdk.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis-backed dataset input configuration bound from {@code pockethive.inputs.redis.*}.
 */
public class RedisDataSetInputProperties implements WorkInputConfig {

    public enum PickStrategy {
        ROUND_ROBIN,
        WEIGHTED_RANDOM
    }

    private boolean enabled = false;
    private String host;
    private int port = 6379;
    private String username;
    private String password;
    private boolean ssl = false;
    private String listName;
    private String sourcesJson;
    private List<Source> sources = List.of();
    private PickStrategy pickStrategy = PickStrategy.ROUND_ROBIN;
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

    public List<Source> getSources() {
        return sources;
    }

    public String getSourcesJson() {
        return sourcesJson;
    }

    public void setSourcesJson(String sourcesJson) {
        this.sourcesJson = normalise(sourcesJson);
    }

    public void setSources(List<Source> sources) {
        if (sources == null || sources.isEmpty()) {
            this.sources = List.of();
            return;
        }
        List<Source> normalised = new ArrayList<>(sources.size());
        for (Source source : sources) {
            if (source == null) {
                continue;
            }
            Source copy = new Source();
            copy.setListName(source.getListName());
            copy.setWeight(source.getWeight());
            normalised.add(copy);
        }
        this.sources = List.copyOf(normalised);
    }

    public PickStrategy getPickStrategy() {
        return pickStrategy;
    }

    public void setPickStrategy(PickStrategy pickStrategy) {
        this.pickStrategy = pickStrategy == null ? PickStrategy.ROUND_ROBIN : pickStrategy;
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

    public static final class Source {
        private String listName;
        private double weight = 1.0;

        public String getListName() {
            return listName;
        }

        public void setListName(String listName) {
            this.listName = normalise(listName);
        }

        public double getWeight() {
            return weight;
        }

        public void setWeight(double weight) {
            this.weight = weight;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Source source)) {
                return false;
            }
            return Double.compare(weight, source.weight) == 0
                && java.util.Objects.equals(listName, source.listName);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(listName, weight);
        }
    }
}
