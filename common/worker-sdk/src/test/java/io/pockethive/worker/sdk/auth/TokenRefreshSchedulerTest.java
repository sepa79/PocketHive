package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TokenRefreshSchedulerTest {

    @Test
    void usesMetadataBuffersWhenRefreshing() {
        InMemoryTokenStore tokenStore = new InMemoryTokenStore();
        long now = Instant.now().getEpochSecond();
        TokenInfo token = new TokenInfo(
            "token",
            "Bearer",
            now + 600,
            now - 1,
            "capture",
            Map.of("tokenUrl", "https://auth.example.test"),
            Map.of(
                "refreshBuffer", 120,
                "emergencyRefreshBuffer", 30
            )
        );
        tokenStore.storeToken("token-key", token);

        AtomicReference<AuthConfig> captured = new AtomicReference<>();
        AuthStrategy strategy = new AuthStrategy() {
            @Override
            public String getType() {
                return "capture";
            }

            @Override
            public TokenInfo refresh(AuthConfig config) {
                captured.set(config);
                return token;
            }

            @Override
            public Map<String, String> generateHeaders(AuthConfig config, TokenInfo token, io.pockethive.worker.sdk.api.WorkItem item) {
                return Map.of();
            }
        };

        AuthProperties properties = new AuthProperties();
        properties.getRefresh().setRefreshAheadSeconds(60);
        properties.getRefresh().setEmergencyRefreshAheadSeconds(10);

        TokenRefreshScheduler scheduler = new TokenRefreshScheduler(
            tokenStore,
            Map.of("capture", strategy),
            new SimpleMeterRegistry(),
            properties
        );

        scheduler.scanAndRefresh();

        AuthConfig used = captured.get();
        assertThat(used).isNotNull();
        assertThat(used.refreshBuffer()).isEqualTo(120);
        assertThat(used.emergencyRefreshBuffer()).isEqualTo(30);
    }
}
