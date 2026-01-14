package io.pockethive.postprocessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.observability.Hop;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

class PostProcessorTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");
    private static final WorkerControlPlaneProperties WORKER_PROPERTIES =
        ControlPlaneTestFixtures.workerProperties("swarm", "postprocessor", "instance");
    private static final Map<String, String> WORKER_QUEUES =
        ControlPlaneTestFixtures.workerQueues("swarm");
    private static final String FINAL_QUEUE = WORKER_QUEUES.get("final");

    @Test
    void onMessageRecordsLatencyAndErrorsAndUpdatesStatus() {
        PostProcessorWorkerProperties properties = workerProperties(false);
        PostProcessorWorkerImpl worker = new PostProcessorWorkerImpl(properties);
        ObservabilityContext context = new ObservabilityContext();
        List<Hop> hops = new ArrayList<>();
        hops.add(new Hop("generator", "gen-1", START, START.plusMillis(5)));
        hops.add(new Hop("processor", "proc-1", START.plusMillis(5), START.plusMillis(15)));
        context.setHops(hops);
        context.setTraceId("trace-123");

        WorkItem message = WorkItem.text("payload")
                .header("x-ph-error", true)
                .header("x-ph-processor-duration-ms", "12")
                .header("x-ph-processor-success", "true")
                .header("x-ph-processor-status", "200")
                .observabilityContext(context)
                .build();

        TestWorkerContext workerContext =
                new TestWorkerContext(new PostProcessorWorkerConfig(false), context);

        WorkItem result = worker.onMessage(message, workerContext);

        assertThat(result).isNull();
        assertThat(workerContext.statusData().get("enabled")).isEqualTo(true);
        assertThat(workerContext.statusData().get("publishAllMetrics")).isEqualTo(false);
        assertThat(workerContext.statusData().get("errors")).isEqualTo(1.0d);
        assertThat(workerContext.statusData().get("hopLatencyMs")).isEqualTo(0L);
        assertThat(workerContext.statusData().get("totalLatencyMs")).isEqualTo(15L);
        assertThat(workerContext.statusData().get("hopCount")).isEqualTo(3);
        assertThat(workerContext.statusData().get("processorTransactions")).isEqualTo(1L);
        assertThat(workerContext.statusData().get("processorSuccessRatio")).isEqualTo(1.0d);
        assertThat(workerContext.statusData().get("processorAvgLatencyMs")).isEqualTo(12.0d);
        assertThat(workerContext.statusData()).doesNotContainKeys("hopDurationsMs", "hopTimeline", "processorCall", "workItemSteps");
        assertThat(workerContext.capturingPublisher().fullSnapshotEmitted()).isFalse();

        MeterRegistry registry = workerContext.meterRegistry();
        var hopSummary = registry.find("ph_hop_latency_ms").summary();
        var totalSummary = registry.find("ph_total_latency_ms").summary();
        var hopCountSummary = registry.find("ph_hops").summary();
        var errorsCounter = registry.find("ph_errors_total").counter();
        var processorLatency = registry.find("ph_processor_latency_ms").summary();
        var processorCalls = registry.find("ph_processor_calls_total").counter();
        var processorSuccessCalls = registry.find("ph_processor_calls_success_total").counter();
        var processorSuccessRatio = registry.find("ph_processor_success_ratio").gauge();
        var processorAvgLatency = registry.find("ph_processor_latency_avg_ms").gauge();

        assertThat(hopSummary).isNotNull();
        assertThat(hopSummary.totalAmount()).isEqualTo(15.0);
        assertThat(hopSummary.count()).isEqualTo(3);
        assertThat(totalSummary).isNotNull();
        assertThat(totalSummary.totalAmount()).isEqualTo(15.0);
        assertThat(hopCountSummary).isNotNull();
        assertThat(hopCountSummary.totalAmount()).isEqualTo(3.0);
        assertThat(errorsCounter).isNotNull();
        assertThat(errorsCounter.count()).isEqualTo(1.0);
        assertThat(processorLatency).isNotNull();
        assertThat(processorLatency.count()).isEqualTo(1);
        assertThat(processorLatency.totalAmount()).isEqualTo(12.0);
        assertThat(processorLatency.takeSnapshot().histogramCounts()).isNotEmpty();
        assertThat(processorCalls).isNotNull();
        assertThat(processorCalls.count()).isEqualTo(1.0);
        assertThat(processorSuccessCalls).isNotNull();
        assertThat(processorSuccessCalls.count()).isEqualTo(1.0);
        assertThat(processorSuccessRatio).isNotNull();
        assertThat(processorSuccessRatio.value()).isEqualTo(1.0);
        assertThat(processorAvgLatency).isNotNull();
        assertThat(processorAvgLatency.value()).isEqualTo(12.0);

        assertThat(registry.find("ph_transaction_hop_duration_ms").gauges()).isNullOrEmpty();
        assertThat(registry.find("ph_transaction_total_latency_ms").gauges()).isNullOrEmpty();
        assertThat(registry.find("ph_transaction_processor_duration_ms").gauges()).isNullOrEmpty();
        assertThat(registry.find("ph_transaction_processor_success").gauges()).isNullOrEmpty();
        assertThat(registry.find("ph_transaction_processor_status").gauges()).isNullOrEmpty();
    }

    @Test
    void onMessageRecordsProcessorFailureMetrics() {
        PostProcessorWorkerProperties properties = workerProperties(false);
        PostProcessorWorkerImpl worker = new PostProcessorWorkerImpl(properties);
        ObservabilityContext context = new ObservabilityContext();
        List<Hop> hops = new ArrayList<>();
        hops.add(new Hop("generator", "gen-1", START, START.plusMillis(5)));
        context.setHops(hops);
        context.setTraceId("trace-789");

        WorkItem message = WorkItem.text("payload")
                .header("x-ph-processor-duration-ms", "30")
                .header("x-ph-processor-success", "false")
                .header("x-ph-processor-status", "500")
                .observabilityContext(context)
                .build();

        TestWorkerContext workerContext =
                new TestWorkerContext(new PostProcessorWorkerConfig(false), context);

        worker.onMessage(message, workerContext);

        assertThat(workerContext.statusData().get("processorTransactions")).isEqualTo(1L);
        assertThat(workerContext.statusData().get("processorSuccessRatio")).isEqualTo(0.0d);
        assertThat(workerContext.statusData().get("processorAvgLatencyMs")).isEqualTo(30.0d);

        MeterRegistry registry = workerContext.meterRegistry();
        var processorLatency = registry.find("ph_processor_latency_ms").summary();
        var processorCalls = registry.find("ph_processor_calls_total").counter();
        var processorSuccessCalls = registry.find("ph_processor_calls_success_total").counter();
        var processorSuccessRatio = registry.find("ph_processor_success_ratio").gauge();
        var processorAvgLatency = registry.find("ph_processor_latency_avg_ms").gauge();

        assertThat(processorLatency).isNotNull();
        assertThat(processorLatency.count()).isEqualTo(1);
        assertThat(processorLatency.totalAmount()).isEqualTo(30.0);
        assertThat(processorLatency.takeSnapshot().histogramCounts()).isNotEmpty();
        assertThat(processorCalls).isNotNull();
        assertThat(processorCalls.count()).isEqualTo(1.0);
        assertThat(processorSuccessCalls).isNotNull();
        assertThat(processorSuccessCalls.count()).isEqualTo(0.0);
        assertThat(processorSuccessRatio).isNotNull();
        assertThat(processorSuccessRatio.value()).isEqualTo(0.0);
        assertThat(processorAvgLatency).isNotNull();
        assertThat(processorAvgLatency.value()).isEqualTo(30.0);
    }

    @Test
    void onMessageCompletesInFlightHopBeforeRecording() {
        PostProcessorWorkerProperties properties = workerProperties(false);
        PostProcessorWorkerImpl worker = new PostProcessorWorkerImpl(properties);
        ObservabilityContext context = new ObservabilityContext();
        List<Hop> hops = new ArrayList<>();
        hops.add(new Hop("generator", "gen-1", START, START.plusMillis(5)));
        hops.add(new Hop("moderator", "mod-1", START.plusMillis(5), START.plusMillis(10)));
        Hop inFlight = new Hop("postprocessor", "instance", START.plusMillis(10), null);
        hops.add(inFlight);
        context.setHops(hops);
        context.setTraceId("trace-456");

        WorkItem message = WorkItem.text("payload")
                .observabilityContext(context)
                .build();

        TestWorkerContext workerContext =
                new TestWorkerContext(new PostProcessorWorkerConfig(false), context);

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
        PostProcessorWorkerProperties properties = workerProperties(false);
        PostProcessorWorkerImpl worker = new PostProcessorWorkerImpl(properties);
        ObservabilityContext context = new ObservabilityContext();
        context.setHops(List.of());

        WorkItem message = WorkItem.text("payload")
                .build();

        TestWorkerContext workerContext = new TestWorkerContext(null, context, false);

        worker.onMessage(message, workerContext);

        assertThat(workerContext.statusData().get("enabled")).isEqualTo(false);
        assertThat(workerContext.statusData().get("publishAllMetrics")).isEqualTo(false);
    }

    @Test
    void onMessagePublishesFullSnapshotWhenPublishAllMetricsEnabled() {
        PostProcessorWorkerProperties properties = workerProperties(true);
        PostProcessorWorkerImpl worker = new PostProcessorWorkerImpl(properties);
        ObservabilityContext context = new ObservabilityContext();
        List<Hop> hops = new ArrayList<>();
        hops.add(new Hop("generator", "gen-1", START, START.plusMillis(5)));
        hops.add(new Hop("processor", "proc-1", START.plusMillis(5), START.plusMillis(15)));
        context.setHops(hops);
        context.setTraceId("trace-456");

        WorkItem message = WorkItem.text("payload")
                .header("x-ph-processor-duration-ms", "42")
                .header("x-ph-processor-success", "true")
                .header("x-ph-processor-status", "200")
                .observabilityContext(context)
                .build();

        TestWorkerContext workerContext =
                new TestWorkerContext(new PostProcessorWorkerConfig(true), context);

        worker.onMessage(message, workerContext);

        Map<String, Object> status = workerContext.statusData();
        assertThat(status.get("publishAllMetrics")).isEqualTo(true);
        assertThat(status).doesNotContainKeys("hopDurationsMs", "hopTimeline", "processorCall", "workItemSteps");
        assertThat(workerContext.capturingPublisher().fullSnapshotEmitted()).isFalse();

        MeterRegistry registry = workerContext.meterRegistry();
        assertThat(registry.find("ph_transaction_hop_duration_ms").gauges()).isNullOrEmpty();
        assertThat(registry.find("ph_transaction_total_latency_ms").gauges()).isNullOrEmpty();
        assertThat(registry.find("ph_transaction_processor_duration_ms").gauges()).isNullOrEmpty();
        assertThat(registry.find("ph_transaction_processor_success").gauges()).isNullOrEmpty();
        assertThat(registry.find("ph_transaction_processor_status").gauges()).isNullOrEmpty();
    }

    private static final class TestWorkerContext implements WorkerContext {
        private final PostProcessorWorkerConfig config;
        private final WorkerInfo info = new WorkerInfo(
            "postprocessor",
            WORKER_PROPERTIES.getSwarmId(),
            WORKER_PROPERTIES.getInstanceId(),
            FINAL_QUEUE,
            null);
        private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        private final CapturingStatusPublisher statusPublisher = new CapturingStatusPublisher();
        private final Logger logger = LoggerFactory.getLogger(PocketHiveWorkerFunction.class);
        private final ObservabilityContext observabilityContext;
        private final boolean enabled;

        private TestWorkerContext(PostProcessorWorkerConfig config, ObservabilityContext observabilityContext) {
            this(config, observabilityContext, true);
        }

        private TestWorkerContext(
            PostProcessorWorkerConfig config,
            ObservabilityContext observabilityContext,
            boolean enabled
        ) {
            this.config = config;
            this.observabilityContext = Objects.requireNonNull(observabilityContext, "observabilityContext");
            this.enabled = enabled;
        }

        @Override
        public WorkerInfo info() {
            return info;
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public <C> C config(Class<C> type) {
            if (config != null && type.isAssignableFrom(PostProcessorWorkerConfig.class)) {
                return type.cast(config);
            }
            return null;
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

        CapturingStatusPublisher capturingPublisher() {
            return statusPublisher;
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
        private boolean fullSnapshotEmitted;

        @Override
        public void update(java.util.function.Consumer<MutableStatus> consumer) {
            Objects.requireNonNull(consumer, "consumer").accept(mutableStatus);
        }

        @Override
        public void emitFull() {
            fullSnapshotEmitted = true;
        }

        boolean fullSnapshotEmitted() {
            return fullSnapshotEmitted;
        }
    }

    private static PostProcessorWorkerProperties workerProperties(boolean publishAllMetrics) {
        PostProcessorWorkerProperties properties = new PostProcessorWorkerProperties(new ObjectMapper(), WORKER_PROPERTIES);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("publishAllMetrics", publishAllMetrics);
        properties.setConfig(config);
        return properties;
    }
}
