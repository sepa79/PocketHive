package io.pockethive.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.observability.ObservabilityContextUtil;
import io.pockethive.processor.transport.TcpBehavior;
import io.pockethive.processor.transport.TcpRequest;
import io.pockethive.processor.transport.TcpResponse;
import io.pockethive.processor.transport.TcpTransport;
import io.pockethive.worker.sdk.api.HttpRequestEnvelope;
import io.pockethive.worker.sdk.api.Iso8583RequestEnvelope;
import io.pockethive.worker.sdk.api.TcpRequestEnvelope;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        when(httpClient.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            ClassicHttpRequest request = invocation.getArgument(0, ClassicHttpRequest.class);
            HttpClientResponseHandler<?> handler = invocation.getArgument(1, HttpClientResponseHandler.class);
            requestRef.set(request);
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(201, "Created");
            response.setHeader(new BasicHeader("content-type", "application/json"));
            response.setEntity(new StringEntity("{\"result\":\"ok\"}", java.nio.charset.StandardCharsets.UTF_8));
            return handler.handleResponse(response);
        });

        WorkItem inbound = inboundItem(Map.of(
                "path", "/api",
                "method", "post",
                "headers", Map.of("X-Test", "true"),
                "body", Map.of("value", 42)
        ));

        WorkItem outbound = invokeThroughObservabilityInterceptor(worker, context, inbound);

        assertThat(outbound).isNotNull();
        JsonNode payload = MAPPER.readTree(outbound.asString());
        assertThat(payload.path("kind").asText()).isEqualTo("http.result");
        assertThat(payload.path("request").path("url").asText()).isEqualTo("http://sut/api");
        assertThat(payload.path("outcome").path("status").asInt()).isEqualTo(201);
        assertThat(payload.path("outcome").path("body").asText()).isEqualTo("{\"result\":\"ok\"}");
        assertThat(outbound.contentType()).isEqualTo("application/json");
        assertThat(outbound.stepHeaders())
                .containsEntry("x-ph-processor-duration-ms", "0")
                .containsEntry("x-ph-processor-success", "true")
                .containsEntry("x-ph-processor-status", "201");

        long stepCount = StreamSupport.stream(outbound.steps().spliterator(), false).count();
        assertThat(stepCount).isEqualTo(2L);

        ObservabilityContext trace = outbound.observabilityContext().orElseThrow();
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
    void workerConcatenatesBaseUrlAndMessagePath() throws Exception {
        ProcessorWorkerProperties properties = newProcessorWorkerProperties();
        properties.setConfig(Map.of("baseUrl", "http://sut/api"));
        HttpClient httpClient = mock(HttpClient.class);
        Clock clock = Clock.fixed(Instant.parse("2024-02-02T00:00:00Z"), ZoneOffset.UTC);
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties, httpClient, httpClient, clock);
        ProcessorWorkerConfig config = new ProcessorWorkerConfig("http://sut/api", null, 0, 0.0, null, null, null, null, null);
        TestWorkerContext context = new TestWorkerContext(config);

        AtomicReference<ClassicHttpRequest> requestRef = new AtomicReference<>();
        when(httpClient.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            ClassicHttpRequest request = invocation.getArgument(0, ClassicHttpRequest.class);
            HttpClientResponseHandler<?> handler = invocation.getArgument(1, HttpClientResponseHandler.class);
            requestRef.set(request);
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
            response.setEntity(new StringEntity("", java.nio.charset.StandardCharsets.UTF_8));
            return handler.handleResponse(response);
        });

        WorkItem inbound = inboundItem(Map.of("path", "/test"));

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
        when(httpClient.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocationOnMock -> {
            HttpClientResponseHandler<?> handler = invocationOnMock.getArgument(1, HttpClientResponseHandler.class);
            if (invocation.getAndIncrement() == 0) {
                BasicClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
                response.setEntity(new StringEntity("ok", java.nio.charset.StandardCharsets.UTF_8));
                return handler.handleResponse(response);
            }
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(502, "Bad Gateway");
            response.setEntity(new StringEntity("bad", java.nio.charset.StandardCharsets.UTF_8));
            return handler.handleResponse(response);
        });

        WorkItem inbound = inboundItem(Map.of("path", "/metrics"));

        WorkItem first = worker.onMessage(inbound, context);
        assertThat(first).isNotNull();
        assertThat(first.stepHeaders())
                .containsEntry("x-ph-processor-duration-ms", "50")
                .containsEntry("x-ph-processor-success", "true")
                .containsEntry("x-ph-processor-status", "200");

        WorkItem second = worker.onMessage(inbound, context);
        assertThat(second).isNotNull();
        assertThat(second.stepHeaders())
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
        when(httpClient.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            ClassicHttpRequest request = invocation.getArgument(0, ClassicHttpRequest.class);
            HttpClientResponseHandler<?> handler = invocation.getArgument(1, HttpClientResponseHandler.class);
            requestRef.set(request);
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
            response.setEntity(new StringEntity("ok", java.nio.charset.StandardCharsets.UTF_8));
            return handler.handleResponse(response);
        });

        WorkItem inbound = inboundItem(Map.of("path", "/defaults"));

        worker.onMessage(inbound, context);

        ClassicHttpRequest request = requestRef.get();
        assertThat(request).isNotNull();
        assertThat(request.getUri()).isEqualTo(URI.create("http://defaults/defaults"));
    }

    @Test
    void workerThrowsWhenBaseUrlMissingAndPathIsRelative() throws Exception {
        ProcessorWorkerProperties properties = newProcessorWorkerProperties();
        properties.setConfig(Map.of("baseUrl", ""));
        HttpClient httpClient = mock(HttpClient.class);
        Clock clock = Clock.systemUTC();
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties, httpClient, httpClient, clock);
        ProcessorWorkerConfig config = new ProcessorWorkerConfig(" ", null, 0, 0.0, null, null, null, null, null);
        TestWorkerContext context = new TestWorkerContext(config);

        WorkItem inbound = inboundItem(Map.of("path", "/noop"));

        assertThatThrownBy(() -> worker.onMessage(inbound, context))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid baseUrl");
        verify(httpClient, never()).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
    }

    @Test
    void workerAllowsAbsolutePathWithoutBaseUrl() throws Exception {
        ProcessorWorkerProperties properties = newProcessorWorkerProperties();
        properties.setConfig(Map.of("baseUrl", ""));
        HttpClient httpClient = mock(HttpClient.class);
        Clock clock = Clock.systemUTC();
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties, httpClient, httpClient, clock);
        ProcessorWorkerConfig config = new ProcessorWorkerConfig(" ", null, 0, 0.0, null, null, null, null, null);
        TestWorkerContext context = new TestWorkerContext(config);

        AtomicReference<ClassicHttpRequest> requestRef = new AtomicReference<>();
        when(httpClient.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            ClassicHttpRequest request = invocation.getArgument(0, ClassicHttpRequest.class);
            HttpClientResponseHandler<?> handler = invocation.getArgument(1, HttpClientResponseHandler.class);
            requestRef.set(request);
            BasicClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
            response.setEntity(new StringEntity("{\"ok\":true}", java.nio.charset.StandardCharsets.UTF_8));
            return handler.handleResponse(response);
        });

        WorkItem inbound = inboundItem(Map.of(
            "method", "POST",
            "path", "https://external.example/api/test",
            "body", Map.of("value", 1)
        ));

        WorkItem result = worker.onMessage(inbound, context);

        assertThat(result).isNotNull();
        JsonNode payload = MAPPER.readTree(result.asString());
        assertThat(payload.path("kind").asText()).isEqualTo("http.result");
        assertThat(payload.path("request").path("baseUrl").isNull()).isTrue();
        assertThat(payload.path("request").path("path").asText()).isEqualTo("https://external.example/api/test");
        assertThat(payload.path("request").path("url").asText()).isEqualTo("https://external.example/api/test");
        assertThat(payload.path("request").path("scheme").asText()).isEqualTo("https");
        assertThat(payload.path("outcome").path("status").asInt()).isEqualTo(200);

        ClassicHttpRequest request = requestRef.get();
        assertThat(request).isNotNull();
        assertThat(request.getUri()).isEqualTo(URI.create("https://external.example/api/test"));
        assertThat(request.getMethod()).isEqualTo("POST");
    }

    @Test
    void workerEmitsTcpResultEnvelope() throws Exception {
        ProcessorWorkerProperties properties = newProcessorWorkerProperties();
        properties.setConfig(Map.of("baseUrl", "tcp://tcp.example:9100"));
        HttpClient httpClient = mock(HttpClient.class);
        Clock clock = Clock.systemUTC();
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties, httpClient, httpClient, clock);
        injectGlobalTcpTransport(worker, new TcpTransport() {
            @Override
            public TcpResponse execute(TcpRequest request, TcpBehavior behavior) {
                return new TcpResponse(200, "pong".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0L);
            }

            @Override
            public void close() {
            }
        });
        ProcessorWorkerConfig config = new ProcessorWorkerConfig("tcp://tcp.example:9100", null, 0, 0.0, null, null, null, null, null);
        TestWorkerContext context = new TestWorkerContext(config);

        WorkItem inbound = inboundTcpItem("request_response", "ping");
        WorkItem result = worker.onMessage(inbound, context);

        assertThat(result).isNotNull();
        JsonNode payload = MAPPER.readTree(result.asString());
        assertThat(payload.path("kind").asText()).isEqualTo("tcp.result");
        assertThat(payload.path("request").path("transport").asText()).isEqualTo("tcp");
        assertThat(payload.path("request").path("scheme").asText()).isEqualTo("tcp");
        assertThat(payload.path("request").path("method").asText()).isEqualTo("REQUEST_RESPONSE");
        assertThat(payload.path("request").path("configuredTarget").asText()).isEqualTo("tcp://tcp.example:9100");
        assertThat(payload.path("request").path("endpoint").asText()).isEqualTo("tcp://tcp.example:9100");
        assertThat(payload.path("outcome").path("type").asText()).isEqualTo("tcp_response");
        assertThat(payload.path("outcome").path("status").asInt()).isEqualTo(200);
        assertThat(payload.path("outcome").path("body").asText()).isEqualTo("pong");
        assertThat(result.stepHeaders())
            .containsEntry("x-ph-processor-success", "true")
            .containsEntry("x-ph-processor-status", "200");
        verify(httpClient, never()).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
    }

    @Test
    void workerEmitsIso8583ResultEnvelope() throws Exception {
        byte[] requestPayload = hex("0200A1B2C3D4");
        byte[] responsePayload = hex("0210CAFEBABE");
        try (IsoTestServer server = new IsoTestServer(responsePayload)) {
            server.start();

            ProcessorWorkerProperties properties = newProcessorWorkerProperties();
            properties.setConfig(Map.of("baseUrl", "tcp://127.0.0.1:" + server.port()));
            HttpClient httpClient = mock(HttpClient.class);
            Clock clock = Clock.fixed(Instant.parse("2024-02-20T12:00:00Z"), ZoneOffset.UTC);
            ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties, httpClient, httpClient, clock);
            ProcessorWorkerConfig config = new ProcessorWorkerConfig(
                "tcp://127.0.0.1:" + server.port(),
                null,
                0,
                0.0,
                null,
                null,
                null,
                null,
                null
            );
            TestWorkerContext context = new TestWorkerContext(config);

            WorkItem inbound = inboundIsoItem("MC_2BYTE_LEN_BIN_BITMAP", "RAW_HEX", "0200A1B2C3D4");
            WorkItem result = worker.onMessage(inbound, context);

            assertThat(result).isNotNull();
            JsonNode payload = MAPPER.readTree(result.asString());
            assertThat(payload.path("kind").asText()).isEqualTo("iso8583.result");
            assertThat(payload.path("request").path("transport").asText()).isEqualTo("iso8583");
            assertThat(payload.path("request").path("scheme").asText()).isEqualTo("tcp");
            assertThat(payload.path("request").path("method").asText()).isEqualTo("SEND");
            assertThat(payload.path("request").path("wireProfileId").asText()).isEqualTo("MC_2BYTE_LEN_BIN_BITMAP");
            assertThat(payload.path("request").path("payloadAdapter").asText()).isEqualTo("RAW_HEX");
            assertThat(payload.path("request").path("payloadBytes").asInt()).isEqualTo(requestPayload.length);
            assertThat(payload.path("outcome").path("type").asText()).isEqualTo("iso8583_response");
            assertThat(payload.path("outcome").path("status").asInt()).isEqualTo(200);
            assertThat(payload.path("outcome").path("responseHex").asText()).isEqualTo("0210CAFEBABE");
            assertThat(result.stepHeaders())
                .containsEntry("x-ph-processor-success", "true")
                .containsEntry("x-ph-processor-status", "200");
            assertThat(server.awaitHandled()).isTrue();
            assertThat(server.lastRequestPayload()).containsExactly(requestPayload);
            verify(httpClient, never()).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
        }
    }

    @Test
    void workerRejectsUnsupportedIso8583PayloadAdapter() throws Exception {
        ProcessorWorkerProperties properties = newProcessorWorkerProperties();
        properties.setConfig(Map.of("baseUrl", "tcp://127.0.0.1:6036"));
        HttpClient httpClient = mock(HttpClient.class);
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties, httpClient, httpClient, Clock.systemUTC());
        ProcessorWorkerConfig config = new ProcessorWorkerConfig("tcp://127.0.0.1:6036", null, 0, 0.0, null, null, null, null, null);
        TestWorkerContext context = new TestWorkerContext(config);

        WorkItem inbound = inboundIsoItem("MC_2BYTE_LEN_BIN_BITMAP", "UNSUPPORTED_ADAPTER", "<fields/>");

        assertThatThrownBy(() -> worker.onMessage(inbound, context))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unsupported ISO8583 payloadAdapter: UNSUPPORTED_ADAPTER");
        verify(httpClient, never()).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
    }

    @Test
    void workerRetriesIso8583WhenTcpRetryConfigured() throws Exception {
        byte[] responsePayload = hex("0210A0B1C2D3");
        try (IsoTestServer server = new IsoTestServer(responsePayload, 1)) {
            server.start();

            ProcessorWorkerProperties properties = newProcessorWorkerProperties();
            properties.setConfig(Map.of("baseUrl", "tcp://127.0.0.1:" + server.port()));
            HttpClient httpClient = mock(HttpClient.class);
            ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties, httpClient, httpClient, Clock.systemUTC());

            TcpTransportConfig transport = new TcpTransportConfig(
                "socket",
                2000,
                3000,
                8192,
                true,
                4,
                true,
                false,
                TcpTransportConfig.ConnectionReuse.NONE,
                1
            );
            ProcessorWorkerConfig config = new ProcessorWorkerConfig(
                "tcp://127.0.0.1:" + server.port(),
                null,
                0,
                0.0,
                null,
                null,
                null,
                null,
                transport
            );

            WorkItem inbound = inboundIsoItem("MC_2BYTE_LEN_BIN_BITMAP", "RAW_HEX", "0200A1B2C3D4");
            WorkItem result = worker.onMessage(inbound, new TestWorkerContext(config));

            assertThat(result).isNotNull();
            JsonNode payload = MAPPER.readTree(result.asString());
            assertThat(payload.path("kind").asText()).isEqualTo("iso8583.result");
            assertThat(payload.path("outcome").path("status").asInt()).isEqualTo(200);
            assertThat(payload.path("outcome").path("responseHex").asText()).isEqualTo("0210A0B1C2D3");
            assertThat(server.awaitHandled()).isTrue();
            assertThat(server.connectionAttempts()).isEqualTo(2);
            verify(httpClient, never()).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
        }
    }

    @Test
    void workerRejectsUnknownIso8583WireProfile() throws Exception {
        ProcessorWorkerProperties properties = newProcessorWorkerProperties();
        properties.setConfig(Map.of("baseUrl", "tcp://127.0.0.1:6036"));
        HttpClient httpClient = mock(HttpClient.class);
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties, httpClient, httpClient, Clock.systemUTC());
        ProcessorWorkerConfig config = new ProcessorWorkerConfig("tcp://127.0.0.1:6036", null, 0, 0.0, null, null, null, null, null);
        TestWorkerContext context = new TestWorkerContext(config);

        WorkItem inbound = inboundIsoItem("UNKNOWN_PROFILE", "RAW_HEX", "0102");

        assertThatThrownBy(() -> worker.onMessage(inbound, context))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unsupported ISO8583 wireProfileId: UNKNOWN_PROFILE");
        verify(httpClient, never()).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
    }

    @Test
    void workerThrowsWhenTcpBaseUrlMissing() throws Exception {
        ProcessorWorkerProperties properties = newProcessorWorkerProperties();
        properties.setConfig(Map.of("baseUrl", ""));
        HttpClient httpClient = mock(HttpClient.class);
        Clock clock = Clock.systemUTC();
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties, httpClient, httpClient, clock);
        ProcessorWorkerConfig config = new ProcessorWorkerConfig(" ", null, 0, 0.0, null, null, null, null, null);
        TestWorkerContext context = new TestWorkerContext(config);

        WorkItem inbound = inboundTcpItem("REQUEST_RESPONSE", "ping");

        assertThatThrownBy(() -> worker.onMessage(inbound, context))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("invalid TCP baseUrl");
    }

    @Test
    void workerRejectsLegacyPayloadWithoutKind() throws Exception {
        ProcessorWorkerProperties properties = newProcessorWorkerProperties();
        properties.setConfig(Map.of("baseUrl", "http://sut"));
        HttpClient httpClient = mock(HttpClient.class);
        Clock clock = Clock.systemUTC();
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties, httpClient, httpClient, clock);
        ProcessorWorkerConfig config = new ProcessorWorkerConfig("http://sut", null, 0, 0.0, null, null, null, null, null);
        TestWorkerContext context = new TestWorkerContext(config);

        WorkItem legacyInbound = WorkItem.json(
            new WorkerInfo("ingress", "swarm", "ingress-instance", null, null),
            Map.of("path", "/legacy", "method", "GET")
        ).build();

        assertThatThrownBy(() -> worker.onMessage(legacyInbound, context))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Missing request kind");
        verify(httpClient, never()).execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class));
    }

    @Test
    void perThreadClientUsesSystemProxyRoutePlanner() throws Exception {
        ProcessorWorkerProperties properties = newProcessorWorkerProperties();
        properties.setConfig(Map.of("baseUrl", "http://sut"));
        ProcessorWorkerImpl worker = new ProcessorWorkerImpl(MAPPER, properties);

        Field handlersField = ProcessorWorkerImpl.class.getDeclaredField("protocolHandlers");
        handlersField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> handlers = (Map<String, Object>) handlersField.get(worker);
        Object httpHandler = handlers.get("HTTP");

        Field perThreadField = httpHandler.getClass().getDeclaredField("perThreadClient");
        perThreadField.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<HttpClient> perThreadClient = (ThreadLocal<HttpClient>) perThreadField.get(httpHandler);
        HttpClient client = perThreadClient.get();

        Field routePlannerField = client.getClass().getDeclaredField("routePlanner");
        routePlannerField.setAccessible(true);
        Object routePlanner = routePlannerField.get(client);

        assertThat(routePlanner).isNotNull();
        assertThat(routePlanner.getClass().getName())
            .isEqualTo("org.apache.hc.client5.http.impl.routing.SystemDefaultRoutePlanner");
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

    private static WorkItem inboundItem(Map<String, Object> payload) {
        WorkerInfo info = new WorkerInfo("ingress", "swarm", "ingress-instance", null, null);
        return WorkItem.json(info, HttpRequestEnvelope.of(new HttpRequestEnvelope.HttpRequest(
            payload.containsKey("method") ? String.valueOf(payload.get("method")) : "GET",
            payload.containsKey("path") ? String.valueOf(payload.get("path")) : "/",
            headersMap(payload.get("headers")),
            payload.get("body")
        ))).build();
    }

    private static WorkItem inboundTcpItem(String behavior, String body) {
        WorkerInfo info = new WorkerInfo("ingress", "swarm", "ingress-instance", null, null);
        return WorkItem.json(info, TcpRequestEnvelope.of(new TcpRequestEnvelope.TcpRequest(
            behavior,
            body,
            Map.of(),
            null,
            1024
        ))).build();
    }

    private static WorkItem inboundIsoItem(String wireProfileId, String payloadAdapter, String payload) {
        return inboundIsoItem(wireProfileId, payloadAdapter, payload, Map.of());
    }

    private static WorkItem inboundIsoItem(String wireProfileId,
                                           String payloadAdapter,
                                           String payload,
                                           Map<String, String> headers) {
        WorkerInfo info = new WorkerInfo("ingress", "swarm", "ingress-instance", null, null);
        return WorkItem.json(info, Iso8583RequestEnvelope.of(new Iso8583RequestEnvelope.Iso8583Request(
            wireProfileId,
            payloadAdapter,
            payload,
            headers,
            null
        ))).build();
    }

    private static byte[] hex(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> headersMap(Object rawHeaders) {
        if (!(rawHeaders instanceof Map<?, ?> headers)) {
            return Map.of();
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        headers.forEach((key, value) -> {
            if (key != null && value != null) {
                normalized.put(String.valueOf(key), String.valueOf(value));
            }
        });
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private static void injectGlobalTcpTransport(ProcessorWorkerImpl worker, TcpTransport transport)
        throws ReflectiveOperationException {
        Field protocolHandlersField = ProcessorWorkerImpl.class.getDeclaredField("protocolHandlers");
        protocolHandlersField.setAccessible(true);
        Map<String, Object> protocolHandlers = (Map<String, Object>) protocolHandlersField.get(worker);
        Object tcpHandler = protocolHandlers.get("TCP");
        if (tcpHandler == null) {
            throw new IllegalStateException("TCP handler not registered");
        }

        Field globalTransportField = tcpHandler.getClass().getDeclaredField("globalTransport");
        globalTransportField.setAccessible(true);
        Object previous = globalTransportField.get(tcpHandler);
        if (previous instanceof TcpTransport previousTransport) {
            previousTransport.close();
        }
        globalTransportField.set(tcpHandler, transport);
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

    private static final class IsoTestServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final byte[] responsePayload;
        private final CountDownLatch handled = new CountDownLatch(1);
        private final AtomicReference<byte[]> lastRequestPayload = new AtomicReference<>();
        private final AtomicInteger failuresBeforeSuccess;
        private final AtomicInteger connectionAttempts = new AtomicInteger(0);
        private volatile Thread acceptThread;

        private IsoTestServer(byte[] responsePayload) throws Exception {
            this(responsePayload, 0);
        }

        private IsoTestServer(byte[] responsePayload, int failuresBeforeSuccess) throws Exception {
            this.serverSocket = new ServerSocket(0);
            this.serverSocket.setReuseAddress(true);
            this.responsePayload = responsePayload;
            this.failuresBeforeSuccess = new AtomicInteger(Math.max(0, failuresBeforeSuccess));
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        void start() {
            acceptThread = new Thread(this::acceptLoop, "iso-test-server");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }

        boolean awaitHandled() throws InterruptedException {
            return handled.await(2, TimeUnit.SECONDS);
        }

        byte[] lastRequestPayload() {
            return lastRequestPayload.get();
        }

        int connectionAttempts() {
            return connectionAttempts.get();
        }

        private void acceptLoop() {
            while (!serverSocket.isClosed()) {
                try (Socket socket = serverSocket.accept();
                     InputStream in = socket.getInputStream();
                     OutputStream out = socket.getOutputStream()) {
                    connectionAttempts.incrementAndGet();
                    byte[] lengthBytes = readFully(in, 2);
                    int length = ((lengthBytes[0] & 0xFF) << 8) | (lengthBytes[1] & 0xFF);
                    byte[] payload = readFully(in, length);
                    lastRequestPayload.set(payload);

                    int remainingFailures = failuresBeforeSuccess.getAndUpdate(v -> v > 0 ? v - 1 : 0);
                    if (remainingFailures > 0) {
                        continue;
                    }

                    int responseLength = responsePayload.length;
                    out.write((responseLength >> 8) & 0xFF);
                    out.write(responseLength & 0xFF);
                    out.write(responsePayload);
                    out.flush();
                    handled.countDown();
                    return;
                } catch (Exception ignored) {
                    return;
                }
            }
        }

        private static byte[] readFully(InputStream in, int length) throws Exception {
            byte[] bytes = new byte[length];
            int offset = 0;
            while (offset < length) {
                int read = in.read(bytes, offset, length - offset);
                if (read < 0) {
                    throw new IllegalStateException("Unexpected end of stream");
                }
                offset += read;
            }
            return bytes;
        }

        @Override
        public void close() throws Exception {
            try {
                serverSocket.close();
            } finally {
                if (acceptThread != null) {
                    acceptThread.join(500);
                }
            }
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
