package io.pockethive.worker.sdk.config;

import io.pockethive.redis.api.RedisSequenceGenerator;
import io.pockethive.redis.config.RedisConnectionSettings;
import io.pockethive.redis.config.RedisConfigurationParser;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Responsibility: apply validated sequence connection updates and own application-scoped generator lifetime.
 * Must not: build Redis clients, format sequences or hold process-global configuration.
 * Contract: RESP-TEMPLATE-SEQUENCE — docs/architecture/runtime-responsibilities.md#resp-template-sequence.
 */
public final class RedisSequenceConfiguration implements AutoCloseable {
    // Operations may run concurrently; shutdown excludes operations and resource creation.
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private boolean closed;

    private final AtomicReference<RedisConnectionSettings> settings;
    private final Map<RedisConnectionSettings, RedisSequenceGenerator> generators = new ConcurrentHashMap<>();
    private final Function<RedisConnectionSettings, RedisSequenceGenerator> generatorFactory;

    public RedisSequenceConfiguration(RedisSequenceProperties properties) {
        this(properties, RedisSequenceGenerator::new);
    }

    RedisSequenceConfiguration(RedisSequenceProperties properties,
                               Function<RedisConnectionSettings, RedisSequenceGenerator> generatorFactory) {
        this.generatorFactory = Objects.requireNonNull(generatorFactory, "generatorFactory");
        // Explicitly approved preservation of legacy enabled=false bootstrap semantics:
        // skip supplied startup settings, retain the canonical default sequence settings.
        var initial = properties.isEnabled() ? properties : new RedisSequenceProperties();
        settings = new AtomicReference<>(initial.connectionSettings(RedisSequenceProperties.PREFIX));
    }
    public RedisConnectionSettings currentSettings() { return settings.get(); }
    public void configureFromWorkerConfig(Map<String, Object> config) {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("Redis resources are closed");
            if (config == null || !config.containsKey("redis")) return;
            if (!(config.get("redis") instanceof Map<?, ?> values)) throw new IllegalArgumentException("redis must be an object");
            settings.updateAndGet(current -> new RedisConfigurationParser().mergeRedisConnection(current, values, "redis"));
        } finally {
            lifecycle.readLock().unlock();
        }
    }
    public String next(String key, String mode, String format, long startOffset, long maxSequence) {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("Redis resources are closed");
            return generators.computeIfAbsent(settings.get(), generatorFactory).next(key, mode, format, startOffset, maxSequence);
        } finally {
            lifecycle.readLock().unlock();
        }
    }
    public boolean reset(String key) {
        lifecycle.readLock().lock();
        try {
            if (closed) throw new IllegalStateException("Redis resources are closed");
            return generators.computeIfAbsent(settings.get(), generatorFactory).reset(key);
        } finally {
            lifecycle.readLock().unlock();
        }
    }
    public void close() {
        lifecycle.writeLock().lock();
        try {
            if (closed) return;
            closed = true;
            RuntimeException failure = null;
            for (var generator : generators.values()) {
                try { generator.close(); } catch (RuntimeException ex) {
                    if (failure == null) failure = ex; else if (failure != ex) failure.addSuppressed(ex);
                }
            }
            generators.clear();
            if (failure != null) throw failure;
        } finally {
            lifecycle.writeLock().unlock();
        }
    }
}
