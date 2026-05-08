package io.pockethive.worker.sdk.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public interface TokenStore extends AutoCloseable {
    TokenRecord get(String tokenKey, String fingerprint);

    ClaimResult claimRefresh(String tokenKey, String fingerprint, RefreshClaim claim, Duration lease);

    void store(TokenRecord token, RefreshClaim claim, Duration cleanupGrace);

    void releaseClaim(String tokenKey, String fingerprint, RefreshClaim claim);

    List<TokenDueRef> claimDueRefreshes(Instant now, int limit, Duration lease);

    @Override
    void close();
}
