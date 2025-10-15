package io.pockethive.postprocessor;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.Topology;
import io.pockethive.observability.Hop;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.MessageWorker;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class PostProcessorTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");

    @Test
    void onMessageRecordsLatencyAndErrorsAndUpdatesStatus() {
        PostProcessorDefaults defaults = new PostProcessorDefaults();
        defaults.setEnabled(true);
        PostProcessorWorkerImpl worker = new PostProcessorWorkerImpl(defaults);
        ObservabilityContext context = new ObservabilityContext();
        List<Hop> hops = new ArrayList<>();
        hops.add(new Hop("generator", "gen-1", START, START.plusMillis(5)));
        hops.add(new Hop("processor", "proc-1", START.plusMillis(5), START.plusMillis(15)));
        context.setHops(hops);
        context.setTraceId("trace-123");

        WorkMessage message = WorkMessage.text("payload")
                .header("x-ph-error", true)
                .observabilityContext(context)
                .build();

        TestWorkerContext workerContext =
                new TestWorkerContext(new PostProcessorWorkerConfig(true), context);

        WorkResult result = worker.onMessage(message, workerContext);

        assertThat(result).isEqualTo(WorkResult.none());
        assertThat(workerContext.statusData().get("enabled")).isEqualTo(true);
        assertThat(workerContext.statusData().get("errors")).isEqualTo(1.0d);
        assertThat(workerContext.statusData().get("hopLatencyMs")).isEqualTo(0L);
        assertThat(workerContext.statusData().get("totalLatencyMs")).isEqualTo(15L);
        assertThat(workerContext.statusData().get("hopCount")).isEqualTo(3);

    MeterRegistry registry = workerContext.meterRegistry();
    var hopSummary = registry.find("ph_hop_latency_ms").summary();
    var totalSummary = registry.find("ph_total_latency_ms").summary();
    var hopCountSummary = registry.find("ph_hops").summary();
    var errorsCounter = registry.find("ph_errors_total").counter();

    assertThat(hopSummary).isNotNull();
    assertThat(hopSummary.totalAmount()).isEqualTo(15.0);
    assertThat(hopSummary.count()).isEqualTo(3);
    assertThat(totalSummary).isNotNull();
    assertThat(totalSummary.totalAmount()).isEqualTo(15.0);
    assertThat(hopCountSummary).isNotNull();
    assertThat(hopCountSummary.totalAmount()).isEqualTo(3.0);
    assertThat(errorsCounter).isNotNull();
    assertThat(errorsCounter.count()).isEqualTo(1.0);
    }

    @Test
    void onMessageCompletesInFlightHopBeforeRecording() {
        PostProcessorDefaults defaults = new PostProcessorDefaults();
        defaults.setEnabled(true);
        PostProcessorWorkerImpl worker = new PostProcessorWorkerImpl(defaults);
        ObservabilityContext context = new ObservabilityContext();
        List<Hop> hops = new ArrayList<>();
        hops.add(new Hop("generator", "gen-1", START, START.plusMillis(5)));
        hops.add(new Hop("moderator", "mod-1", START.plusMillis(5), START.plusMillis(10)));
        Hop inFlight = new Hop("postprocessor", "instance", START.plusMillis(10), null);
        hops.add(inFlight);
        context.setHops(hops);
        context.setTraceId("trace-456");

        WorkMessage message = WorkMessage.text("payload")
                .observabilityContext(context)
                .build();

        TestWorkerContext workerContext =
                new TestWorkerContext(new PostProcessorWorkerConfig(true), context);

        worker.onMessage(message, workerContext);

        assertThat(inFlight.getProcessedAt()).isEqualTo(START.plusMillis(10));

        MeterRegistry registry = workerContext.meterRegistry();
        var hopSummary = registry.find("ph_hop_latency_ms").summary();
        var totalSummary = registry.find("ph_total_latency_ms").summary();
        var hopCountSummary = registry.find("ph_hops").summary();

        assertThat(hopSummary).isNotNull();
        assertThat(hopSummary.count()).isEqualTo(3);
        assertThat(hopSummary.totalAmount()).isEqualTo(10.0);
        assertThat(totalSummary).isNotNull();
        assertThat(totalSummary.totalAmount()).isEqualTo(10.0);
        assertThat(hopCountSummary).isNotNull();
        assertThat(hopCountSummary.totalAmount()).isEqualTo(3.0);
    }

    @Test
    void onMessageUsesDefaultsWhenNoConfigPresent() {
        PostProcessorDefaults defaults = new PostProcessorDefaults();
        defaults.setEnabled(false);
        PostProcessorWorkerImpl worker = new PostProcessorWorkerImpl(defaults);
        ObservabilityContext context = new ObservabilityContext();
        context.setHops(List.of());

        WorkMessage message = WorkMessage.text("payload")
                .build();

        TestWorkerContext workerContext = new TestWorkerContext(null, context);

        worker.onMessage(message, workerContext);

        assertThat(workerContext.statusData().get("enabled")).isEqualTo(false);
    }

    private static final class TestWorkerContext implements WorkerContext {
        private final PostProcessorWorkerConfig config;
        private final WorkerInfo info = new WorkerInfo("postprocessor", "swarm", "instance", Topology.FINAL_QUEUE, null);
        private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        private final CapturingStatusPublisher statusPublisher = new CapturingStatusPublisher();
        private final Logger logger = LoggerFactory.getLogger(MessageWorker.class);
        private final ObservabilityContext observabilityContext;

        private TestWorkerContext(PostProcessorWorkerConfig config, ObservabilityContext observabilityContext) {
            this.config = config;
            this.observabilityContext = Objects.requireNonNull(observabilityContext, "observabilityContext");
        }

        @Override
        public WorkerInfo info() {
            return info;
        }

        @Override
        public <C> Optional<C> config(Class<C> type) {
            if (config != null && type.isAssignableFrom(PostProcessorWorkerConfig.class)) {
                return Optional.of(type.cast(config));
            }
            return Optional.empty();
        }

        @Override
        public StatusPublisher statusPublisher() {
            return statusPublisher;
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public MeterRegistry meterRegistry() {
            return meterRegistry;
        }

        @Override
        public io.micrometer.observation.ObservationRegistry observationRegistry() {
            return io.micrometer.observation.ObservationRegistry.create();
        }

        @Override
        public ObservabilityContext observabilityContext() {
            return observabilityContext;
        }

        Map<String, Object> statusData() {
            return Map.copyOf(statusPublisher.data);
        }
    }

    private static final class CapturingStatusPublisher implements StatusPublisher {
        private final Map<String, Object> data = new LinkedHashMap<>();
        private final MutableStatus mutableStatus = new MutableStatus() {
            @Override
            public MutableStatus data(String key, Object value) {
                data.put(key, value);
                return this;
            }
        };

        @Override
        public void update(java.util.function.Consumer<MutableStatus> consumer) {
            Objects.requireNonNull(consumer, "consumer").accept(mutableStatus);
        }
    }
}
