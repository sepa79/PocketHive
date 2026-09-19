package io.pockethive.worker.sdk.auth;

import io.pockethive.redis.config.RedisConnectionSettings;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Responsibility: allocate and release owned auth runtime resources while preserving borrowed resources.
 * Must not: resolve profiles, apply credentials, acquire tokens or change token records and leases.
 * Contract: RESP-WORK-AUTH-RESOURCES -- docs/architecture/runtime-responsibilities.md#resp-work-auth-resources.
 */
final class AuthRuntimeResources implements AutoCloseable {
    private final TokenStore tokenStore;
    private final HttpClient httpClient;
    private final boolean owned;
    private final AtomicBoolean closed = new AtomicBoolean();

    AuthRuntimeResources(TokenStore tokenStore, HttpClient httpClient) {
        this(tokenStore, httpClient, true);
    }

    private AuthRuntimeResources(TokenStore tokenStore, HttpClient httpClient, boolean owned) {
        this.tokenStore = tokenStore;
        this.httpClient = httpClient;
        this.owned = owned;
    }

    static AuthRuntimeResources redis(String swarmId, RedisConnectionSettings settings) {
        return create(new RedisTokenStore(swarmId, settings));
    }

    static AuthRuntimeResources withoutTokenStore() {
        return create(null);
    }

    static AuthRuntimeResources borrowed(TokenStore tokenStore, HttpClient httpClient) {
        return new AuthRuntimeResources(tokenStore, httpClient, false);
    }

    private static AuthRuntimeResources create(TokenStore tokenStore) {
        try {
            HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            return new AuthRuntimeResources(tokenStore, httpClient);
        } catch (RuntimeException | Error failure) {
            boolean interrupted = Thread.interrupted();
            try {
                if (tokenStore != null) {
                    tokenStore.close();
                }
            } catch (RuntimeException | Error cleanupFailure) {
                if (failure != cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            throw failure;
        }
    }

    TokenStore tokenStore() {
        return tokenStore;
    }

    HttpClient httpClient() {
        return httpClient;
    }

    @Override
    public void close() {
        if (!owned || !closed.compareAndSet(false, true)) {
            return;
        }
        boolean interrupted = Thread.interrupted();
        try {
            Throwable failure = null;
            try {
                if (tokenStore != null) {
                    tokenStore.close();
                }
            } catch (RuntimeException | Error cleanupFailure) {
                failure = cleanupFailure;
            }
            interrupted |= Thread.interrupted();
            try {
                httpClient.close();
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

    void closeAfterFailure(Throwable failure) {
        try {
            close();
        } catch (RuntimeException | Error cleanupFailure) {
            if (failure != cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }
}
