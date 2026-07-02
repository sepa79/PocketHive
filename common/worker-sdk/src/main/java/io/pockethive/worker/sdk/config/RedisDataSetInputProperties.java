package io.pockethive.worker.sdk.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis-backed dataset input configuration bound from {@code pockethive.inputs.redis.*}.
 */
public class RedisDataSetInputProperties implements WorkInputConfig {

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;
    private static final double MIN_RATE_PER_SEC = 0.0;

    public enum PickStrategy {
        ROUND_ROBIN,
        WEIGHTED_RANDOM
    }

    private boolean enabled = false;
    private String host;
    private Integer port;
    private String username;
    private String password;
    private Boolean ssl;
    private String listName;
    private List<Source> sources;
    private PickStrategy pickStrategy;
    private Double ratePerSec;
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
        return requirePresent(port, "port");
    }

    public void setPort(int port) {
        this.port = port;
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
        return requirePresent(ssl, "ssl");
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
        return sources == null ? List.of() : sources;
    }

    public void setSources(List<Source> sources) {
        if (sources == null || sources.isEmpty()) {
            this.sources = List.of();
            return;
        }
        List<Source> normalised = new ArrayList<>(sources.size());
        int index = 0;
        for (Source source : sources) {
            if (source == null) {
                throw new IllegalArgumentException("sources[" + index + "] must be an object");
            }
            validateSource(source, "sources[" + index + "]");
            Source copy = new Source();
            copy.setListName(source.getListName());
            copy.setWeight(source.getWeight());
            normalised.add(copy);
            index++;
        }
        this.sources = List.copyOf(normalised);
    }

    public PickStrategy getPickStrategy() {
        return pickStrategy;
    }

    public void setPickStrategy(PickStrategy pickStrategy) {
        this.pickStrategy = pickStrategy;
    }

    public double getRatePerSec() {
        return requireRatePerSec(ratePerSec, "ratePerSec");
    }

    public void setRatePerSec(double ratePerSec) {
        this.ratePerSec = ratePerSec;
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

    @Override
    public void validateConfigured(String prefix) {
        requireNonBlank(host, prefix + ".host");
        requirePort(port, prefix + ".port");
        requirePresent(ssl, prefix + ".ssl");
        requirePresent(pickStrategy, prefix + ".pickStrategy");
        requireRatePerSec(ratePerSec, prefix + ".ratePerSec");
        boolean hasListName = listName != null;
        boolean hasSources = !getSources().isEmpty();
        if (hasListName == hasSources) {
            throw new IllegalStateException(
                prefix + " must configure exactly one source mode: listName or sources");
        }
        for (int i = 0; i < getSources().size(); i++) {
            validateSource(getSources().get(i), prefix + ".sources[" + i + "]");
        }
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be configured");
        }
        return value;
    }

    private static <T> T requirePresent(T value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " must be configured");
        }
        return value;
    }

    private static int requirePort(Integer value, String name) {
        int port = requirePresent(value, name);
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalStateException(name + " must be between " + MIN_PORT + " and " + MAX_PORT);
        }
        return port;
    }

    private static double requireRatePerSec(Double value, String name) {
        double rate = requirePresent(value, name);
        if (!Double.isFinite(rate) || rate < MIN_RATE_PER_SEC) {
            throw new IllegalStateException(name + " must be >= " + MIN_RATE_PER_SEC);
        }
        return rate;
    }

    private static void validateSource(Source source, String prefix) {
        requireNonBlank(source.getListName(), prefix + ".listName");
        double weight = requirePresent(source.weight, prefix + ".weight");
        if (!Double.isFinite(weight) || weight <= 0.0) {
            throw new IllegalStateException(prefix + ".weight must be > 0");
        }
    }

    public static final class Source {
        private String listName;
        private Double weight;

        public String getListName() {
            return listName;
        }

        public void setListName(String listName) {
            this.listName = normalise(listName);
        }

        public double getWeight() {
            return requirePresent(weight, "weight");
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
            return java.util.Objects.equals(weight, source.weight)
                && java.util.Objects.equals(listName, source.listName);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(listName, weight);
        }
    }
}
