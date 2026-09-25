package io.pockethive.worker.sdk.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Responsibility: define authentication token storage operations.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-AUTH-TOKEN-STORE — docs/architecture/runtime-responsibilities.md#resp-auth-token-store.
 */
public interface TokenStore extends AutoCloseable {
    /** Returns null for a missing token; mismatched fingerprint or malformed storage throws. */
    TokenRecord get(String tokenKey, String fingerprint);

    /** Atomically acquire a lease; storage unavailability propagates as an exception. */
    ClaimResult claimRefresh(String tokenKey, String fingerprint, RefreshClaim claim, Duration lease);

    void store(TokenRecord token, RefreshClaim claim, Duration cleanupGrace);

    void releaseClaim(String tokenKey, String fingerprint, RefreshClaim claim);

    /** List due candidates without acquiring a lease. Call claimRefresh before refreshing. */
    List<TokenDueRef> listDueRefreshes(Instant now, int limit);

    @Override
    void close();
}
