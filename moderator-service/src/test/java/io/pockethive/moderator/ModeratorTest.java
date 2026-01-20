package io.pockethive.moderator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
        properties = new ModeratorWorkerProperties(new ObjectMapper(), WORKER_PROPERTIES);
        properties.setConfig(Map.of(
            "mode", Map.of(
                "type", "pass-through",
                "ratePerSec", 0.0,
                "sine", Map.of(
                    "minRatePerSec", 0.0,
                    "maxRatePerSec", 0.0,
                    "periodSeconds", 60.0,
                    "phaseOffsetSeconds", 0.0))));
        worker = new ModeratorWorkerImpl(properties);
    }

    @Test
    void onMessageReturnsForwardedPayload() {
        WorkItem message = WorkItem.text(new WorkerInfo("ingress", "swarm", "instance", null, null), "test")
                .header("original", "true")
                .build();

        WorkItem result = worker.onMessage(message, new TestWorkerContext(
            new ModeratorWorkerConfig(ModeratorWorkerConfig.Mode.passThrough())));

        assertThat(result).isNotNull();
        assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo("test");
        assertThat(result.headers()).containsEntry("original", "true");
    }

    @Test
    void usesDefaultsWhenConfigMissing() {
        WorkItem message = WorkItem.text(new WorkerInfo("ingress", "swarm", "instance", null, null), "payload")
                .build();

        WorkItem result = worker.onMessage(message, new TestWorkerContext(null));
        assertThat(result).isNotNull();
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
        public <C> C config(Class<C> type) {
            if (config != null && type.isAssignableFrom(ModeratorWorkerConfig.class)) {
                return type.cast(config);
            }
            return null;
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
