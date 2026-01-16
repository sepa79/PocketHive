package io.pockethive.worker.sdk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.auth.strategies.BasicAuthStrategy;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class AuthHeaderGeneratorTest {

    @Test
    void generateSkipsRefreshForStaticStrategy() {
        InMemoryTokenStore tokenStore = new InMemoryTokenStore();
        AuthHeaderGenerator generator = new AuthHeaderGenerator(
            tokenStore,
            Map.of("basic-auth", new BasicAuthStrategy()),
            new AuthConfigRegistry()
        );

        AuthConfig config = new AuthConfig(
            "basic-auth",
            "basic:auth",
            60,
            10,
            Map.of("username", "user", "password", "pass")
        );

        Map<String, String> headers = generator.generate(
            new TestWorkerContext(),
            config,
            WorkItem.text("payload").build()
        );

        assertThat(headers).containsEntry("Authorization", "Basic dXNlcjpwYXNz");
        assertThat(tokenStore.getAllTokens()).isEmpty();
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
