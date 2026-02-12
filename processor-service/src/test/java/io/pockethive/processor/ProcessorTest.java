package io.pockethive.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerInvocationContext;
import io.pockethive.worker.sdk.runtime.WorkerObservabilityInterceptor;
import io.pockethive.worker.sdk.runtime.WorkerState;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import java.net.URI;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final WorkerControlPlaneProperties WORKER_PROPERTIES =
        ControlPlaneTestFixtures.workerProperties("swarm", "processor", "instance");
    private static final Map<String, String> WORKER_QUEUES =
        ControlPlaneTestFixtures.workerQueues("swarm");
    private static final String MODERATOR_QUEUE = WORKER_QUEUES.get("moderator");
    private static final String FINAL_QUEUE = WORKER_QUEUES.get("final");
    private static final String TRAFFIC_EXCHANGE = ControlPlaneTestFixtures.hiveExchange("swarm");

    @Test
    void workerInvokesHttpAndPropagatesResponse() throws Exception {
        ProcessorWorkerProperties properties = newProcessorWorkerProperties();
        properties.setConfig(Map.of("baseUrl", "http://sut"));
        HttpClient httpClient = mock(HttpClient.class);
        Clock clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties, httpClient, httpClient, clock);
        ProcessorWorkerConfig config = new ProcessorWorkerConfig("http://sut", null, 0, 0.0, null, null, null, null,null);
        TestWorkerContext context = new TestWorkerContext(config);

        AtomicReference<ClassicHttpRequest> requestRef = new AtomicReference<>();
        when(httpClient.execute(any(ClassicHttpRequest.class))).thenAnswer(invocation -> {
            ClassicHttpRequest request = invocation.getArgument(0, ClassicHttpRequest.class);
            requestRef.set(request);
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(201, "Created");
            response.setHeader(new BasicHeader("content-type", "application/json"));
            response.setEntity(new StringEntity("{\"result\":\"ok\"}", java.nio.charset.StandardCharsets.UTF_8));
            return response;
        });

        WorkItem inbound = WorkItem.json(Map.of(
                "path", "/api",
                "method", "post",
                "headers", Map.of("X-Test", "true"),
                "body", Map.of("value", 42)
        )).build();

        WorkItem outbound = invokeThroughObservabilityInterceptor(worker, context, inbound);

        assertThat(outbound).isNotNull();
        JsonNode payload = MAPPER.readTree(outbound.asString());
        assertThat(payload.path("status").asInt()).isEqualTo(201);
        assertThat(payload.path("body").asText()).isEqualTo("{\"result\":\"ok\"}");
        assertThat(outbound.headers())
                .containsEntry("content-type", "application/json")
                .containsEntry("x-ph-service", "processor")
                .containsEntry("x-ph-processor-duration-ms", "0")
                .containsEntry("x-ph-processor-success", "true")
                .containsEntry("x-ph-processor-status", "201");

        long stepCount = StreamSupport.stream(outbound.steps().spliterator(), false).count();
        assertThat(stepCount).isEqualTo(2L);

        String traceHeader = (String) outbound.headers().get(ObservabilityContextUtil.HEADER);
        ObservabilityContext trace = ObservabilityContextUtil.fromHeader(traceHeader);
        assertThat(trace.getHops()).hasSize(2);
        assertThat(trace.getHops().get(0).getService()).isEqualTo("ingress");
        assertThat(trace.getHops().get(1).getService()).isEqualTo("processor");
        assertThat(context.observabilityContext().getHops()).hasSize(2);

        ClassicHttpRequest request = requestRef.get();
        assertThat(request).isNotNull();
        assertThat(request.getUri()).isEqualTo(URI.create("http://sut/api"));
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getFirstHeader("X-Test").getValue()).isEqualTo("true");

        assertThat(context.statusData())
                .containsEntry("baseUrl", "http://sut")
                .containsEntry("enabled", true)
                .containsEntry("transactions", 1L)
                .containsEntry("successRatio", 1.0)
                .containsEntry("avgLatencyMs", 0.0);
    }

    @Test
    void workerExtractsBusinessOutcomeHeadersFromTemplateRules() throws Exception {
        ProcessorWorkerProperties properties = newProcessorWorkerProperties();
        properties.setConfig(Map.of("baseUrl", "http://sut"));
        HttpClient httpClient = mock(HttpClient.class);
        Clock clock = Clock.fixed(Instant.parse("2024-03-01T00:00:00Z"), ZoneOffset.UTC);
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties, httpClient, httpClient, clock);
        ProcessorWorkerConfig config = new ProcessorWorkerConfig("http://sut", null, 0, 0.0, null, null, null, null, null);
        TestWorkerContext context = new TestWorkerContext(config);

        when(httpClient.execute(any(ClassicHttpRequest.class))).thenAnswer(invocation -> {
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
            response.setEntity(new StringEntity("{\"resultCode\":\"00\"}", java.nio.charset.StandardCharsets.UTF_8));
            return response;
        });

        WorkItem inbound = WorkItem.json(Map.of(
            "path", "/api",
            "method", "post",
            "headers", Map.of("X-Customer-Segment", "retail"),
            "body", "{\"customerCode\":\"custA\",\"amount\":100}",
            "resultRules", Map.of(
                "businessCode", Map.of(
                    "source", "RESPONSE_BODY",
                    "pattern", "\"resultCode\":\"([^\"]+)\""
                ),
                "successRegex", "^(00)$",
                "dimensions", List.of(
                    Map.of(
                        "name", "customer_code",
                        "source", "REQUEST_BODY",
                        "pattern", "\"customerCode\":\"([^\"]+)\""
                    ),
                    Map.of(
                        "name", "segment",
                        "source", "REQUEST_HEADER",
                        "header", "X-Customer-Segment",
                        "pattern", "(.+)"
                    )
                )
            )
        )).header("x-ph-call-id", "redis-auth").build();

        WorkItem outbound = worker.onMessage(inbound, context);

        assertThat(outbound).isNotNull();
        assertThat(outbound.headers())
            .containsEntry("x-ph-business-code", "00")
            .containsEntry("x-ph-business-success", "true")
            .containsEntry("x-ph-dim-customer_code", "custA")
            .containsEntry("x-ph-dim-segment", "retail")
            .containsEntry("x-ph-call-id", "redis-auth");
    }

    @Test
    void workerConcatenatesBaseUrlAndMessagePath() throws Exception {
        ProcessorWorkerProperties properties = newProcessorWorkerProperties();
        properties.setConfig(Map.of("baseUrl", "http://sut/api"));
        HttpClient httpClient = mock(HttpClient.class);
        Clock clock = Clock.fixed(Instant.parse("2024-02-02T00:00:00Z"), ZoneOffset.UTC);
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties, httpClient, httpClient, clock);
        ProcessorWorkerConfig config = new ProcessorWorkerConfig("http://sut/api", null, 0, 0.0, null, null, null, null, null);
        TestWorkerContext context = new TestWorkerContext(config);

        AtomicReference<ClassicHttpRequest> requestRef = new AtomicReference<>();
        when(httpClient.execute(any(ClassicHttpRequest.class))).thenAnswer(invocation -> {
            ClassicHttpRequest request = invocation.getArgument(0, ClassicHttpRequest.class);
            requestRef.set(request);
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
            response.setEntity(new StringEntity("", java.nio.charset.StandardCharsets.UTF_8));
            return response;
        });

        WorkItem inbound = WorkItem.json(Map.of("path", "/test")).build();

        worker.onMessage(inbound, context);

        ClassicHttpRequest request = requestRef.get();
        assertThat(request).isNotNull();
        assertThat(request.getUri()).isEqualTo(URI.create("http://sut/api/test"));
    }

    @Test
    void workerTracksRollingMetricsAcrossCalls() throws Exception {
        ProcessorWorkerProperties properties = newProcessorWorkerProperties();
        properties.setConfig(Map.of("baseUrl", "http://sut"));
        HttpClient httpClient = mock(HttpClient.class);
        SequenceClock clock = new SequenceClock(0, 50, 100, 250);
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties, httpClient, httpClient, clock);
        ProcessorWorkerConfig config = new ProcessorWorkerConfig("http://sut", null, 0, 0.0, null, null, null, null, null);
        TestWorkerContext context = new TestWorkerContext(config);

        AtomicInteger invocation = new AtomicInteger();
        when(httpClient.execute(any(ClassicHttpRequest.class))).thenAnswer(invocationOnMock -> {
            ClassicHttpRequest request = invocationOnMock.getArgument(0, ClassicHttpRequest.class);
            if (invocation.getAndIncrement() == 0) {
                BasicClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
                response.setEntity(new StringEntity("ok", java.nio.charset.StandardCharsets.UTF_8));
                return response;
            }
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(502, "Bad Gateway");
            response.setEntity(new StringEntity("bad", java.nio.charset.StandardCharsets.UTF_8));
            return response;
        });

        WorkItem inbound = WorkItem.json(Map.of("path", "/metrics")).build();

        WorkItem first = worker.onMessage(inbound, context);
        assertThat(first).isNotNull();
        assertThat(first.headers())
                .containsEntry("x-ph-processor-duration-ms", "50")
                .containsEntry("x-ph-processor-success", "true")
                .containsEntry("x-ph-processor-status", "200");

        WorkItem second = worker.onMessage(inbound, context);
        assertThat(second).isNotNull();
        assertThat(second.headers())
                .containsEntry("x-ph-processor-duration-ms", "150")
                .containsEntry("x-ph-processor-success", "false")
                .containsEntry("x-ph-processor-status", "502");

        Map<String, Object> status = context.statusData();
        assertThat(status)
                .containsEntry("transactions", 2L);
        assertThat((Double) status.get("successRatio")).isEqualTo(0.5);
        assertThat((Double) status.get("avgLatencyMs")).isEqualTo(100.0);
    }

    @Test
    void workerFallsBackToDefaultsWhenConfigMissing() throws Exception {
        ProcessorWorkerProperties properties = newProcessorWorkerProperties();
        properties.setConfig(Map.of("baseUrl", "http://defaults"));
        HttpClient httpClient = mock(HttpClient.class);
        Clock clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties, httpClient, httpClient, clock);
        TestWorkerContext context = new TestWorkerContext(null);

        AtomicReference<ClassicHttpRequest> requestRef = new AtomicReference<>();
        when(httpClient.execute(any(ClassicHttpRequest.class))).thenAnswer(invocation -> {
            ClassicHttpRequest request = invocation.getArgument(0, ClassicHttpRequest.class);
            requestRef.set(request);
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
            response.setEntity(new StringEntity("ok", java.nio.charset.StandardCharsets.UTF_8));
            return response;
        });

        WorkItem inbound = WorkItem.json(Map.of("path", "/defaults" )).build();

        worker.onMessage(inbound, context);

        ClassicHttpRequest request = requestRef.get();
        assertThat(request).isNotNull();
        assertThat(request.getUri()).isEqualTo(URI.create("http://defaults/defaults"));
    }

    @Test
    void workerReturnsErrorWhenBaseUrlMissing() throws Exception {
        ProcessorWorkerProperties properties = newProcessorWorkerProperties();
        properties.setConfig(Map.of("baseUrl", ""));
        HttpClient httpClient = mock(HttpClient.class);
        Clock clock = Clock.systemUTC();
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties, httpClient, httpClient, clock);
        ProcessorWorkerConfig config = new ProcessorWorkerConfig(" ", null, 0, 0.0, null, null, null, null, null);
        TestWorkerContext context = new TestWorkerContext(config);

        WorkItem inbound = WorkItem.json(Map.of("path", "/noop")).build();

        WorkItem result = worker.onMessage(inbound, context);

        assertThat(result).isNotNull();
        JsonNode payload = MAPPER.readTree(result.asString());
        assertThat(payload.path("error").asText()).isEqualTo("invalid baseUrl");
        assertThat(result.headers())
            .containsEntry("x-ph-processor-duration-ms", "0")
            .containsEntry("x-ph-processor-success", "false")
            .containsEntry("x-ph-processor-status", "-1");
        verify(httpClient, never()).execute(any(ClassicHttpRequest.class));
    }

    private WorkItem invokeThroughObservabilityInterceptor(ProcessorWorkerImpl worker,
                                                           TestWorkerContext context,
                                                           WorkItem inbound) throws Exception {
        WorkerDefinition definition = processorDefinition();
        WorkerObservabilityInterceptor interceptor = new WorkerObservabilityInterceptor();
        WorkerState state = instantiateWorkerState(definition);
        WorkerInvocationContext invocationContext = instantiateInvocationContext(definition, state, context, inbound);
        return interceptor.intercept(invocationContext, invocation ->
                worker.onMessage(invocation.message(), invocation.workerContext()));
    }

    private static WorkerDefinition processorDefinition() {
        return new WorkerDefinition(
                "processorWorker",
                ProcessorWorkerImpl.class,
                WorkerInputType.RABBITMQ,
                "processor",
                WorkIoBindings.of(MODERATOR_QUEUE, FINAL_QUEUE, TRAFFIC_EXCHANGE),
                ProcessorWorkerConfig.class,
                WorkInputConfig.class,
                WorkOutputConfig.class,
                WorkerOutputType.RABBITMQ,
                "Processor worker",
                Set.of(WorkerCapability.MESSAGE_DRIVEN)
        );
    }

    private static WorkerState instantiateWorkerState(WorkerDefinition definition) {
        try {
            Constructor<WorkerState> constructor = WorkerState.class.getDeclaredConstructor(WorkerDefinition.class);
            constructor.setAccessible(true);
            return constructor.newInstance(definition);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to construct WorkerState", ex);
        }
    }

    private static WorkerInvocationContext instantiateInvocationContext(WorkerDefinition definition,
                                                                         WorkerState state,
                                                                         WorkerContext workerContext,
                                                                         WorkItem message) {
        try {
            Constructor<WorkerInvocationContext> constructor = WorkerInvocationContext.class
                    .getDeclaredConstructor(WorkerDefinition.class, WorkerState.class, WorkerContext.class, WorkItem.class);
            constructor.setAccessible(true);
            return constructor.newInstance(definition, state, workerContext, message);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Unable to construct WorkerInvocationContext", ex);
        }
    }

    private static final class TestWorkerContext implements WorkerContext {
        private final ProcessorWorkerConfig config;
        private final WorkerInfo info = new WorkerInfo(
                "processor",
                WORKER_PROPERTIES.getSwarmId(),
                WORKER_PROPERTIES.getInstanceId(),
                MODERATOR_QUEUE,
                FINAL_QUEUE);
        private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        private final ObservabilityContext observability;
        private final CapturingStatusPublisher statusPublisher = new CapturingStatusPublisher();

        private TestWorkerContext(ProcessorWorkerConfig config) {
            this(config, defaultObservability());
        }

        private TestWorkerContext(ProcessorWorkerConfig config, ObservabilityContext observability) {
            this.config = config;
            this.observability = observability;
        }

        private static ObservabilityContext defaultObservability() {
            return ObservabilityContextUtil.init("ingress", "ingress-instance", "swarm");
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
            if (config != null && type.isAssignableFrom(ProcessorWorkerConfig.class)) {
                return type.cast(config);
            }
            return null;
        }

        @Override
        public StatusPublisher statusPublisher() {
            return statusPublisher;
        }

        @Override
        public org.slf4j.Logger logger() {
            return org.slf4j.LoggerFactory.getLogger(PocketHiveWorkerFunction.class);
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
            return observability;
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
            consumer.accept(mutableStatus);
        }
    }

    private static final class SequenceClock extends Clock {
        private final long[] sequence;
        private final ZoneOffset zone = ZoneOffset.UTC;
        private int index;

        private SequenceClock(long... sequence) {
            if (sequence.length == 0) {
                throw new IllegalArgumentException("sequence must not be empty");
            }
            this.sequence = sequence;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public long millis() {
            if (index < sequence.length) {
                return sequence[index++];
            }
            return sequence[sequence.length - 1];
        }

        @Override
        public Instant instant() {
            int current = Math.max(0, Math.min(index - 1, sequence.length - 1));
            return Instant.ofEpochMilli(sequence[current]);
        }
    }

    private ProcessorWorkerProperties newProcessorWorkerProperties() {
        return new ProcessorWorkerProperties(MAPPER, WORKER_PROPERTIES);
    }
}
