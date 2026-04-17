package io.pockethive.auth.client;

import io.pockethive.auth.contract.SessionResponseDto;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class AuthServiceServiceTokenProvider {
    private final AuthServiceClient authServiceClient;
    private final String serviceName;
    private final String serviceSecret;
    private final Clock clock;
    private final Duration refreshSkew;

    private volatile CachedToken cachedToken;

    public AuthServiceServiceTokenProvider(AuthServiceClient authServiceClient, String serviceName, String serviceSecret) {
        this(authServiceClient, serviceName, serviceSecret, Clock.systemUTC(), Duration.ofMinutes(1));
    }

    AuthServiceServiceTokenProvider(AuthServiceClient authServiceClient,
                                    String serviceName,
                                    String serviceSecret,
                                    Clock clock,
                                    Duration refreshSkew) {
        this.authServiceClient = Objects.requireNonNull(authServiceClient, "authServiceClient");
        this.serviceName = requireText(serviceName, "serviceName");
        this.serviceSecret = requireText(serviceSecret, "serviceSecret");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refreshSkew = Objects.requireNonNull(refreshSkew, "refreshSkew");
    }

    public String getAuthorizationHeader() {
        CachedToken snapshot = cachedToken;
        Instant now = clock.instant();
        if (snapshot != null && snapshot.expiresAt().isAfter(now.plus(refreshSkew))) {
            return snapshot.authorizationHeader();
        }
        synchronized (this) {
            snapshot = cachedToken;
            now = clock.instant();
            if (snapshot != null && snapshot.expiresAt().isAfter(now.plus(refreshSkew))) {
                return snapshot.authorizationHeader();
            }
            SessionResponseDto session = authServiceClient.serviceLogin(serviceName, serviceSecret);
            CachedToken refreshed = new CachedToken(
                session.tokenType() + " " + session.accessToken(),
                session.expiresAt()
            );
            cachedToken = refreshed;
            return refreshed.authorizationHeader();
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be null or blank");
        }
        return value.trim();
    }

    private record CachedToken(String authorizationHeader, Instant expiresAt) {
        private CachedToken {
            Objects.requireNonNull(authorizationHeader, "authorizationHeader");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
