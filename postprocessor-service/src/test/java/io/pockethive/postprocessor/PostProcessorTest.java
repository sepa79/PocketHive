package io.pockethive.postprocessor;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.observability.Hop;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.worker.sdk.api.MessageWorker;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
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

import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;

class PostProcessorTest {

    private static final Instant START = Instant.parse("2024-01-01T00:00:00Z");
    private static final WorkerControlPlaneProperties WORKER_PROPERTIES =
        ControlPlaneTestFixtures.workerProperties("swarm", "postprocessor", "instance");
    private static final String FINAL_QUEUE = WORKER_PROPERTIES.getQueues().get("final");

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
                .header("x-ph-processor-duration-ms", "12")
                .header("x-ph-processor-success", "true")
                .header("x-ph-processor-status", "200")
                .observabilityContext(context)
                .build();

        TestWorkerContext workerContext =
                new TestWorkerContext(new PostProcessorWorkerConfig(true, false), context);

        WorkResult result = worker.onMessage(message, workerContext);

        assertThat(result).isEqualTo(WorkResult.none());
        assertThat(workerContext.statusData().get("enabled")).isEqualTo(true);
        assertThat(workerContext.statusData().get("publishAllMetrics")).isEqualTo(false);
        assertThat(workerContext.statusData().get("errors")).isEqualTo(1.0d);
        assertThat(workerContext.statusData().get("hopLatencyMs")).isEqualTo(0L);
        assertThat(workerContext.statusData().get("totalLatencyMs")).isEqualTo(15L);
        assertThat(workerContext.statusData().get("hopCount")).isEqualTo(3);
        assertThat(workerContext.statusData().get("processorTransactions")).isEqualTo(1L);
        assertThat(workerContext.statusData().get("processorSuccessRatio")).isEqualTo(1.0d);
        assertThat(workerContext.statusData().get("processorAvgLatencyMs")).isEqualTo(12.0d);
        assertThat(workerContext.statusData()).doesNotContainKeys("hopDurationsMs", "hopTimeline", "processorCall");
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
        PostProcessorDefaults defaults = new PostProcessorDefaults();
        defaults.setEnabled(true);
        PostProcessorWorkerImpl worker = new PostProcessorWorkerImpl(defaults);
        ObservabilityContext context = new ObservabilityContext();
        List<Hop> hops = new ArrayList<>();
        hops.add(new Hop("generator", "gen-1", START, START.plusMillis(5)));
        context.setHops(hops);
        context.setTraceId("trace-789");

        WorkMessage message = WorkMessage.text("payload")
                .header("x-ph-processor-duration-ms", "30")
                .header("x-ph-processor-success", "false")
                .header("x-ph-processor-status", "500")
                .observabilityContext(context)
                .build();

        TestWorkerContext workerContext =
                new TestWorkerContext(new PostProcessorWorkerConfig(true, false), context);

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
                new TestWorkerContext(new PostProcessorWorkerConfig(true, false), context);

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
        assertThat(workerContext.statusData().get("publishAllMetrics")).isEqualTo(false);
    }

    @Test
    void onMessagePublishesFullSnapshotWhenPublishAllMetricsEnabled() {
        PostProcessorDefaults defaults = new PostProcessorDefaults();
        defaults.setEnabled(true);
        defaults.setPublishAllMetrics(true);
        PostProcessorWorkerImpl worker = new PostProcessorWorkerImpl(defaults);
        ObservabilityContext context = new ObservabilityContext();
        List<Hop> hops = new ArrayList<>();
        hops.add(new Hop("generator", "gen-1", START, START.plusMillis(5)));
        hops.add(new Hop("processor", "proc-1", START.plusMillis(5), START.plusMillis(15)));
        context.setHops(hops);
        context.setTraceId("trace-456");

        WorkMessage message = WorkMessage.text("payload")
                .header("x-ph-processor-duration-ms", "42")
                .header("x-ph-processor-success", "true")
                .header("x-ph-processor-status", "200")
                .observabilityContext(context)
                .build();

        TestWorkerContext workerContext =
                new TestWorkerContext(new PostProcessorWorkerConfig(true, true), context);

        worker.onMessage(message, workerContext);

        Map<String, Object> status = workerContext.statusData();
        assertThat(status.get("publishAllMetrics")).isEqualTo(true);
        assertThat(status).containsEntry("hopDurationsMs", List.of(5L, 10L, 0L));
        assertThat(status).containsKey("hopTimeline");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> timeline = (List<Map<String, Object>>) status.get("hopTimeline");
        assertThat(timeline).hasSize(3);
        assertThat(timeline.get(0)).containsEntry("service", "generator");
        assertThat(timeline.get(1)).containsEntry("service", "processor");
        assertThat(timeline.get(2)).containsEntry("service", "postprocessor");
        assertThat(status).containsKey("processorCall");
        @SuppressWarnings("unchecked")
        Map<String, Object> processorCall = (Map<String, Object>) status.get("processorCall");
        assertThat(processorCall).containsEntry("durationMs", 42L);
        assertThat(processorCall).containsEntry("success", true);
        assertThat(processorCall).containsEntry("statusCode", 200);
        assertThat(workerContext.capturingPublisher().fullSnapshotEmitted()).isTrue();

        MeterRegistry registry = workerContext.meterRegistry();
        List<Gauge> hopGauges = new ArrayList<>(registry.find("ph_transaction_hop_duration_ms").gauges());
        assertThat(hopGauges).hasSize(3);
        assertThat(hopGauges.stream().map(Gauge::value).collect(toList()))
                .containsExactlyInAnyOrder(5.0, 10.0, 0.0);
        assertThat(hopGauges.stream().map(g -> g.getId().getTag("hop_service")).collect(toList()))
                .containsExactlyInAnyOrder("generator", "processor", "postprocessor");
        assertThat(hopGauges.stream().map(g -> g.getId().getTag("transaction_seq")).distinct().collect(toList()))
                .containsExactly("1");

        List<Gauge> totalGauges = new ArrayList<>(registry.find("ph_transaction_total_latency_ms").gauges());
        assertThat(totalGauges).hasSize(1);
        assertThat(totalGauges.get(0).value()).isEqualTo(15.0);
        assertThat(totalGauges.get(0).getId().getTag("transaction_seq")).isEqualTo("1");

        List<Gauge> processorDuration = new ArrayList<>(registry.find("ph_transaction_processor_duration_ms").gauges());
        assertThat(processorDuration).hasSize(1);
        assertThat(processorDuration.get(0).value()).isEqualTo(42.0);

        List<Gauge> processorSuccess = new ArrayList<>(registry.find("ph_transaction_processor_success").gauges());
        assertThat(processorSuccess).hasSize(1);
        assertThat(processorSuccess.get(0).value()).isEqualTo(1.0);

        List<Gauge> processorStatus = new ArrayList<>(registry.find("ph_transaction_processor_status").gauges());
        assertThat(processorStatus).hasSize(1);
        assertThat(processorStatus.get(0).value()).isEqualTo(200.0);
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
}
