package io.pockethive.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.Topology;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.worker.sdk.api.MessageWorker;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkMessage;
import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.config.WorkerType;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRegistry;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import io.pockethive.worker.sdk.transport.rabbit.RabbitWorkMessageConverter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void workerInvokesHttpAndPropagatesResponse() throws Exception {
        ProcessorDefaults defaults = new ProcessorDefaults();
        defaults.setEnabled(true);
        defaults.setBaseUrl("http://sut/");
        HttpClient httpClient = mock(HttpClient.class);
        Clock clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(defaults, httpClient, clock);
        ProcessorWorkerConfig config = new ProcessorWorkerConfig(true, "http://sut/");
        TestWorkerContext context = new TestWorkerContext(config);

        AtomicReference<HttpRequest> requestRef = new AtomicReference<>();
        when(httpClient.send(any(), any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0, HttpRequest.class);
            requestRef.set(request);
            return SimpleHttpResponse.from(request, 201, Map.of("content-type", List.of("application/json")), "{\"result\":\"ok\"}");
        });

        WorkMessage inbound = WorkMessage.json(Map.of(
                "path", "/api",
                "method", "post",
                "headers", Map.of("X-Test", "true"),
                "body", Map.of("value", 42)
        )).build();

        WorkResult result = worker.onMessage(inbound, context);

        assertThat(result).isInstanceOf(WorkResult.Message.class);
        WorkMessage outbound = ((WorkResult.Message) result).value();
        JsonNode payload = MAPPER.readTree(outbound.asString());
        assertThat(payload.path("status").asInt()).isEqualTo(201);
        assertThat(payload.path("body").asText()).isEqualTo("{\"result\":\"ok\"}");
        assertThat(outbound.headers())
                .containsEntry("content-type", "application/json")
                .containsEntry("x-ph-service", "processor");

        String traceHeader = (String) outbound.headers().get(ObservabilityContextUtil.HEADER);
        ObservabilityContext trace = ObservabilityContextUtil.fromHeader(traceHeader);
        assertThat(trace.getHops()).hasSize(1);
        assertThat(trace.getHops().get(0).getService()).isEqualTo("processor");

        HttpRequest request = requestRef.get();
        assertThat(request).isNotNull();
        assertThat(request.uri()).isEqualTo(URI.create("http://sut/api"));
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.headers().firstValue("X-Test")).contains("true");

        MeterRegistry registry = context.meterRegistry();
        var counter = registry.find("processor_messages_total")
            .tag("service", "processor")
            .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0d);

        assertThat(context.statusData())
                .containsEntry("baseUrl", "http://sut/")
                .containsEntry("enabled", true);
    }

    @Test
    void workerFallsBackToDefaultsWhenConfigMissing() throws Exception {
        ProcessorDefaults defaults = new ProcessorDefaults();
        defaults.setEnabled(true);
        defaults.setBaseUrl("http://defaults/");
        HttpClient httpClient = mock(HttpClient.class);
        Clock clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(defaults, httpClient, clock);
        TestWorkerContext context = new TestWorkerContext(null);

        AtomicReference<HttpRequest> requestRef = new AtomicReference<>();
        when(httpClient.send(any(), any())).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0, HttpRequest.class);
            requestRef.set(request);
            return SimpleHttpResponse.from(request, 200, Map.of(), "ok");
        });

        WorkMessage inbound = WorkMessage.json(Map.of("path", "/defaults" )).build();

        worker.onMessage(inbound, context);

        HttpRequest request = requestRef.get();
        assertThat(request).isNotNull();
        assertThat(request.uri()).isEqualTo(URI.create("http://defaults/defaults"));
    }

    @Test
    void workerReturnsErrorWhenBaseUrlMissing() throws Exception {
        ProcessorDefaults defaults = new ProcessorDefaults();
        defaults.setEnabled(true);
        defaults.setBaseUrl("");
        HttpClient httpClient = mock(HttpClient.class);
        Clock clock = Clock.systemUTC();
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(defaults, httpClient, clock);
        ProcessorWorkerConfig config = new ProcessorWorkerConfig(true, " ");
        TestWorkerContext context = new TestWorkerContext(config);

        WorkMessage inbound = WorkMessage.json(Map.of("path", "/noop" )).build();

        WorkResult result = worker.onMessage(inbound, context);

        assertThat(result).isInstanceOf(WorkResult.Message.class);
        JsonNode payload = MAPPER.readTree(((WorkResult.Message) result).value().asString());
        assertThat(payload.path("error").asText()).isEqualTo("invalid baseUrl");
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    void runtimeDispatchesWorkToWorkerAndPublishesResult() throws Exception {
        WorkerRuntime workerRuntime = mock(WorkerRuntime.class);
        WorkerRegistry workerRegistry = mock(WorkerRegistry.class);
        WorkerControlPlaneRuntime controlPlaneRuntime = mock(WorkerControlPlaneRuntime.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        RabbitListenerEndpointRegistry listenerRegistry = mock(RabbitListenerEndpointRegistry.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(listenerRegistry.getListenerContainer("processorWorkerListener")).thenReturn(container);
        when(container.isRunning()).thenReturn(false);

        WorkerDefinition definition = new WorkerDefinition(
                "processorWorker",
                ProcessorWorkerImpl.class,
                WorkerType.MESSAGE,
                "processor",
                Topology.MOD_QUEUE,
                Topology.FINAL_QUEUE,
                ProcessorWorkerConfig.class
        );
        when(workerRegistry.all()).thenReturn(List.of(definition));

        ControlPlaneIdentity identity = new ControlPlaneIdentity("swarm", "processor", "instance");
        ProcessorDefaults defaults = new ProcessorDefaults();
        defaults.setEnabled(true);
        defaults.setBaseUrl("http://sut/");
        ProcessorRuntimeAdapter adapter = new ProcessorRuntimeAdapter(
                workerRuntime,
                workerRegistry,
                controlPlaneRuntime,
                rabbitTemplate,
                listenerRegistry,
                identity,
                defaults
        );

        adapter.initialiseStateListener();

        WorkMessage outbound = WorkMessage.text("processed").build();
        when(workerRuntime.dispatch(eq("processorWorker"), any(WorkMessage.class)))
                .thenReturn(WorkResult.message(outbound));

        RabbitWorkMessageConverter converter = new RabbitWorkMessageConverter();
        Message inbound = converter.toMessage(WorkMessage.text("incoming").build());

        adapter.onWork(inbound);

        verify(workerRuntime).dispatch(eq("processorWorker"), any(WorkMessage.class));
        verify(rabbitTemplate).send(eq(Topology.EXCHANGE), eq(Topology.FINAL_QUEUE), any(Message.class));
        verify(controlPlaneRuntime).emitStatusSnapshot();
    }

    @Test
    void runtimeDelegatesControlMessages() {
        WorkerRuntime workerRuntime = mock(WorkerRuntime.class);
        WorkerRegistry workerRegistry = mock(WorkerRegistry.class);
        WorkerControlPlaneRuntime controlPlaneRuntime = mock(WorkerControlPlaneRuntime.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        RabbitListenerEndpointRegistry listenerRegistry = mock(RabbitListenerEndpointRegistry.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(listenerRegistry.getListenerContainer("processorWorkerListener")).thenReturn(container);
        when(container.isRunning()).thenReturn(false);

        WorkerDefinition definition = new WorkerDefinition(
                "processorWorker",
                ProcessorWorkerImpl.class,
                WorkerType.MESSAGE,
                "processor",
                Topology.MOD_QUEUE,
                Topology.FINAL_QUEUE,
                ProcessorWorkerConfig.class
        );
        when(workerRegistry.all()).thenReturn(List.of(definition));

        ControlPlaneIdentity identity = new ControlPlaneIdentity("swarm", "processor", "instance");
        ProcessorDefaults defaults = new ProcessorDefaults();
        defaults.setEnabled(false);
        defaults.setBaseUrl("");
        ProcessorRuntimeAdapter adapter = new ProcessorRuntimeAdapter(
                workerRuntime,
                workerRegistry,
                controlPlaneRuntime,
                rabbitTemplate,
                listenerRegistry,
                identity,
                defaults
        );

        adapter.initialiseStateListener();

        adapter.onControl("{}", "processor.control", null);
        verify(controlPlaneRuntime).handle("{}", "processor.control");

        assertThatThrownBy(() -> adapter.onControl(" ", "processor.control", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payload");

        assertThatThrownBy(() -> adapter.onControl("{}", " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("routing key");

        // ensure MDC was cleared during processing despite exceptions
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    private static final class TestWorkerContext implements WorkerContext {
        private final ProcessorWorkerConfig config;
        private final WorkerInfo info = new WorkerInfo("processor", "swarm", "instance", Topology.MOD_QUEUE, Topology.FINAL_QUEUE);
        private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        private final ObservabilityContext observability;
        private final CapturingStatusPublisher statusPublisher = new CapturingStatusPublisher();

        private TestWorkerContext(ProcessorWorkerConfig config) {
            this.config = config;
            this.observability = ObservabilityContextUtil.init(info.role(), info.instanceId(), info.swarmId());
            this.observability.getHops().clear();
        }

        @Override
        public WorkerInfo info() {
            return info;
        }

        @Override
        public <C> Optional<C> config(Class<C> type) {
            if (config != null && type.isAssignableFrom(ProcessorWorkerConfig.class)) {
                return Optional.of(type.cast(config));
            }
            return Optional.empty();
        }

        @Override
        public StatusPublisher statusPublisher() {
            return statusPublisher;
        }

        @Override
        public org.slf4j.Logger logger() {
            return org.slf4j.LoggerFactory.getLogger(MessageWorker.class);
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

    private record SimpleHttpResponse(HttpRequest request,
                                      int status,
                                      HttpHeaders headers,
                                      String body) implements HttpResponse<String> {

        private static SimpleHttpResponse from(HttpRequest request,
                                               int status,
                                               Map<String, List<String>> headers,
                                               String body) {
            return new SimpleHttpResponse(request, status, HttpHeaders.of(headers, (a, b) -> true), body);
        }

        @Override
        public int statusCode() {
            return status;
        }

        @Override
        public HttpRequest request() {
            return request;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return headers;
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return request.uri();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
