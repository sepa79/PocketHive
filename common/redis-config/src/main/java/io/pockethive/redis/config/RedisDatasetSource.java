package io.pockethive.redis.config;

import java.util.Objects;

/**
 * Responsibility: decode Redis dataset list names and retain immutable source name/weight values.
 * Must not: select a source, validate collection uniqueness, render expressions or access Redis.
 * Contract: RESP-WORK-REDIS-SOURCES — docs/architecture/runtime-responsibilities.md#resp-work-redis-sources.
 */
public final class RedisDatasetSource {
    private final String listName;
    private final double weight;

    public RedisDatasetSource(Object listName, Object weight) {
        this.listName = decodeName(listName);
        this.weight = decodeWeight(weight);
    }

    public String getListName() {
        return listName;
    }

    public double getWeight() {
        return weight;
    }

    static String decodeName(Object value) {
        String normalized = decodeOptionalName(value);
        if (normalized == null) {
            throw new IllegalArgumentException("Redis dataset source listName must be nonblank text.");
        }
        return normalized;
    }

    static String decodeOptionalName(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Redis dataset source listName must be nonblank text.");
        }
        String normalized = text.trim();
        return normalized.isBlank() ? null : normalized;
    }

    static double decodeWeight(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("Redis dataset source weight must be configured.");
        }
        double weight;
        if (value instanceof Number number) {
            weight = number.doubleValue();
        } else if (value instanceof String text) {
            try {
                weight = Double.parseDouble(text.trim());
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Redis dataset source weight must be a number.", error);
            }
        } else {
            throw new IllegalArgumentException("Redis dataset source weight must be a number.");
        }
        if (!Double.isFinite(weight) || weight <= 0.0) {
            throw new IllegalArgumentException("Redis dataset source weight must be > 0 and finite.");
        }
        return weight;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RedisDatasetSource source
            && listName.equals(source.listName) && Double.compare(weight, source.weight) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(listName, weight);
    }
}
