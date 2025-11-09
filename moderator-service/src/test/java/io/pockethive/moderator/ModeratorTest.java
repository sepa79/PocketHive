package io.pockethive.moderator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModeratorTest {

    private ModeratorWorkerProperties properties;
    private ModeratorWorkerImpl worker;
    private static final WorkerControlPlaneProperties WORKER_PROPERTIES =
        ControlPlaneTestFixtures.workerProperties("swarm", "moderator", "instance");
    private static final Map<String, String> WORKER_QUEUES =
        ControlPlaneTestFixtures.workerQueues("swarm");
    private static final String IN_QUEUE = WORKER_QUEUES.get("generator");
    private static final String OUT_QUEUE = WORKER_QUEUES.get("moderator");

    @BeforeEach
    void setUp() {
        properties = new ModeratorWorkerProperties(new ObjectMapper());
        worker = new ModeratorWorkerImpl(properties);
    }

    @Test
    void onMessageReturnsForwardedPayload() {
        WorkMessage message = WorkMessage.builder()
                .body("test".getBytes(StandardCharsets.UTF_8))
                .header("original", "true")
                .build();

        WorkResult result = worker.onMessage(message, new TestWorkerContext(
            new ModeratorWorkerConfig(ModeratorWorkerConfig.Mode.passThrough())));

        assertThat(result).isInstanceOf(WorkResult.Message.class);
        WorkMessage forwarded = ((WorkResult.Message) result).value();
        assertThat(new String(forwarded.body(), StandardCharsets.UTF_8)).isEqualTo("test");
        assertThat(forwarded.headers()).containsEntry("original", "true");
        assertThat(forwarded.headers()).containsEntry("x-ph-service", "moderator");
    }

    @Test
    void usesDefaultsWhenConfigMissing() {
        WorkMessage message = WorkMessage.builder()
                .textBody("payload")
                .build();

        WorkResult result = worker.onMessage(message, new TestWorkerContext(null));

        WorkMessage forwarded = ((WorkResult.Message) result).value();
        assertThat(forwarded.headers()).containsEntry("x-ph-service", "moderator");
    }

    private static final class TestWorkerContext implements WorkerContext {

        private final ModeratorWorkerConfig config;
        private final WorkerInfo info = new WorkerInfo(
                "moderator",
                WORKER_PROPERTIES.getSwarmId(),
                WORKER_PROPERTIES.getInstanceId(),
                IN_QUEUE,
                OUT_QUEUE);

        private TestWorkerContext(ModeratorWorkerConfig config) {
            this.config = config;
        }

        @Override
        public WorkerInfo info() {
            return info;
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public <C> Optional<C> config(Class<C> type) {
            if (config != null && type.isAssignableFrom(ModeratorWorkerConfig.class)) {
                return Optional.of(type.cast(config));
            }
            return Optional.empty();
        }

        @Override
        public StatusPublisher statusPublisher() {
            return StatusPublisher.NO_OP;
        }

        @Override
        public org.slf4j.Logger logger() {
            return org.slf4j.LoggerFactory.getLogger("test");
        }

        @Override
        public io.micrometer.core.instrument.MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Override
        public io.micrometer.observation.ObservationRegistry observationRegistry() {
            return io.micrometer.observation.ObservationRegistry.create();
        }

        @Override
        public io.pockethive.observability.ObservabilityContext observabilityContext() {
            return new io.pockethive.observability.ObservabilityContext();
        }
    }
}
