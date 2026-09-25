package io.pockethive.redis.api;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.pockethive.redis.config.RedisConnectionSettings;
import java.util.Objects;
import java.util.function.Function;

/**
 * Responsibility: write expiring diagnostics and release the lazily owned Redis resources.
 * Must not: project captures, select keys, manage tokens or resolve connection defaults.
 * Contract: RESP-WORK-REDIS-DEBUG-CAPTURE - docs/architecture/runtime-responsibilities.md#resp-work-redis-debug-capture.
 */
public final class RedisDebugCaptureStore implements AutoCloseable {
    private final RedisURI uri;
    private final Function<RedisURI, RedisClient> clientFactory;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private boolean closed;

    public RedisDebugCaptureStore(RedisConnectionSettings settings) {
        this(settings, RedisClient::create);
    }

    RedisDebugCaptureStore(RedisConnectionSettings settings, Function<RedisURI, RedisClient> clientFactory) {
        Objects.requireNonNull(settings, "settings");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        this.uri = RedisConnections.uri(settings);
    }

    /** A failed optional diagnostic write must not fail the worker's journey. */
    public synchronized boolean store(String key, int ttlSeconds, String value) {
        if (closed) {
            return false;
        }
        try {
            if (connection == null) {
                open();
            }
            connection.sync().setex(key, ttlSeconds, value);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private void open() {
        client = clientFactory.apply(uri);
        try {
            connection = client.connect();
        } catch (RuntimeException | Error failure) {
            try {
                release();
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            release();
        }
    }

    private void release() {
        StatefulRedisConnection<String, String> ownedConnection = connection;
        RedisClient ownedClient = client;
        connection = null;
        client = null;
        boolean interrupted = Thread.interrupted();
        try {
            Throwable failure = null;
            try {
                if (ownedConnection != null) {
                    ownedConnection.close();
                }
            } catch (RuntimeException | Error cleanupFailure) {
                failure = cleanupFailure;
            }
            interrupted |= Thread.interrupted();
            try {
                if (ownedClient != null) {
                    ownedClient.shutdown();
                }
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
