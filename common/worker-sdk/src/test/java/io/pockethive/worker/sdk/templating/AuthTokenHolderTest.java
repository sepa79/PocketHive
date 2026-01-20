package io.pockethive.worker.sdk.templating;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.auth.AuthConfig;
import io.pockethive.worker.sdk.auth.AuthConfigRegistry;
import io.pockethive.worker.sdk.auth.AuthHeaderGenerator;
import io.pockethive.worker.sdk.auth.AuthStrategy;
import io.pockethive.worker.sdk.auth.InMemoryTokenStore;
import io.pockethive.worker.sdk.auth.TokenInfo;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AuthTokenHolderTest {

    @Test
    void cacheHitSetsAuthTokenHolder() {
        InMemoryTokenStore tokenStore = new InMemoryTokenStore();
        long now = Instant.now().getEpochSecond();
        TokenInfo token = new TokenInfo(
            "cached-token",
            "Bearer",
            now + 3600,
            now + 1800,
            "test-strategy",
            Map.of(),
            Map.of()
        );
        tokenStore.storeToken("token-key", token);

        AuthStrategy strategy = new AuthStrategy() {
            @Override
            public String getType() {
                return "test-strategy";
            }

            @Override
            public Map<String, String> generateHeaders(AuthConfig config, TokenInfo token, WorkItem item) {
                return Map.of("Authorization", "Bearer " + token.accessToken());
            }
        };

        AuthHeaderGenerator generator = new AuthHeaderGenerator(
            tokenStore,
            Map.of("test-strategy", strategy),
            new AuthConfigRegistry()
        );

        AuthConfig config = new AuthConfig("test-strategy", "token-key", 60, 10, Map.of());
        TestWorkerContext context = new TestWorkerContext();
        WorkItem item = WorkItem.text(context.info(), "payload").build();

        try {
            generator.generate(context, config, item);
            assertThat(AuthTokenHolder.getToken("token-key")).isEqualTo("cached-token");
        } finally {
            AuthTokenHolder.clear();
        }
    }

    private static final class TestWorkerContext implements WorkerContext {

        private final WorkerInfo info = new WorkerInfo("worker", "swarm", "instance", null, null);

        @Override
        public WorkerInfo info() {
            return info;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public <C> C config(Class<C> type) {
            return null;
        }

        @Override
        public StatusPublisher statusPublisher() {
            return StatusPublisher.NO_OP;
        }

        @Override
        public org.slf4j.Logger logger() {
            return LoggerFactory.getLogger("auth-test");
        }

        @Override
        public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Override
        public ObservationRegistry observationRegistry() {
            return ObservationRegistry.create();
        }

        @Override
        public ObservabilityContext observabilityContext() {
            return new ObservabilityContext();
        }
    }
}
