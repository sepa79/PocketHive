package io.pockethive.httpsequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.requesttemplates.PortableHttpCallParser;
import io.pockethive.requesttemplates.PortableHttpCall;
import io.pockethive.requesttemplates.PortableResponseExtractor;
import io.pockethive.requesttemplates.files.TemplateLoader;
import io.pockethive.templatedata.JsonDocumentReader;
import io.pockethive.templatedata.TemplateDataException;
import io.pockethive.templating.PebbleTemplateRenderer;
import io.pockethive.templating.PortableHttpBinding;
import io.pockethive.templating.PortableHttpRenderer;
import io.pockethive.templating.api.DisabledSequenceAccess;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import io.pockethive.worker.sdk.auth.AuthFailureException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class HttpSequencePortableRunnerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkerInfo info = new WorkerInfo("http-sequence", "swarm", "worker", null, null);
    private final WorkerContext context = mock(WorkerContext.class);
    private final List<URI> targets = new ArrayList<>();
    private final List<RenderedCall> requests = new ArrayList<>();
    private final ArrayDeque<HttpCallResult> responses = new ArrayDeque<>();
    private final HttpCallExecutor executor = new HttpCallExecutor() {
        @Override
        public HttpCallResult execute(URI target, RenderedCall request) {
            return recordRequest(target, request);
        }

        @Override
        public HttpCallResult executeOnce(URI target, RenderedCall request) {
            return recordRequest(target, request);
        }
    };

    @TempDir
    Path root;

    @BeforeEach
    void configureContext() {
        when(context.info()).thenReturn(info);
        when(context.logger()).thenReturn(LoggerFactory.getLogger(HttpSequencePortableRunnerTest.class));
        when(context.statusPublisher()).thenReturn(StatusPublisher.NO_OP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"account-json", "card-soap11", "card-soap12"})
    void actualRunnerMatchesSharedFixtureContractAndPreservesRecordData(String fixture) throws Exception {
        PortableHttpCall call = loadFixture(fixture);
        String kind = call.response().kind().name();
        String body = kind.equals("JSON") ? "{\"accountNumber\":\"000123\"}"
            : envelope(kind, """
                <changed:CreateCardResponse xmlns:changed="urn:cards"><changed:cardNumber>000123</changed:cardNumber></changed:CreateCardResponse>
                """);
        responses.add(response(call.response().expectedStatus(), body));
        Map<String, Object> record = Map.of("customerId", "000123", "accountNumber", "000123",
            "unused", "keep", "count", 7, "active", true);
        Map<String, Object> params = Map.of("productClass", "gold & \"plus\"");
        var config = config(List.of(fixtureStep(call.serviceId())), params);
        var sharedRequest = new PortableHttpRenderer().render(call,
            new PortableHttpBinding(URI.create(config.baseUrl()), record, params));

        WorkItem output;
        try (var runner = runner()) {
            output = runner.run(seed(mapper.writeValueAsString(record)), context, config);
        }

        assertThat(targets).containsExactly(sharedRequest.uri());
        assertThat(targets.getFirst()).isEqualTo(URI.create("https://sut/base/service"));
        assertThat(requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo(sharedRequest.method());
            assertThat(request.body()).isEqualTo(sharedRequest.body()).contains("000123");
            assertThat(request.headers()).containsExactlyInAnyOrderEntriesOf(sharedRequest.headers());
            if (kind.equals("JSON")) {
                assertThat(new JsonDocumentReader().readObject(request.body()))
                    .containsEntry("customerId", "000123").containsEntry("productClass", params.get("productClass"));
            }
        });
        Map<String, Object> outputRecord = new JsonDocumentReader().readObject(output.payload());
        assertThat(outputRecord).containsAllEntriesOf(record)
            .containsAllEntriesOf(new PortableResponseExtractor().extract(call, call.response().expectedStatus(), body));
        assertThat(output.steps()).hasSize(2);
        assertThat(output.stepHeaders()).containsEntry(HttpSequenceHeaders.ATTEMPTS, 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOAP11", "SOAP12"})
    void faultAtHttp200StopsWithoutExecutingNextStep(String kind) throws Exception {
        loadFixture("card-" + kind.toLowerCase());
        responses.add(response(200, envelope(kind, "<env:Fault/>")));
        try (var runner = runner()) {
            assertThatThrownBy(() -> runner.run(seed("{\"accountNumber\":\"000123\"}"), context,
                config(List.of(fixtureStep("cards"), fixtureStep("cards")), Map.of("productClass", "gold"))))
                .isInstanceOf(TemplateDataException.class).hasMessageContaining("Fault");
        }
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"account-json", "card-soap11", "card-soap12"})
    void transportFailureStopsJsonAndSoapJourneysWithTheOriginalDiagnostic(String fixture) throws Exception {
        PortableHttpCall call = loadFixture(fixture);
        responses.add(new HttpCallResult(-1, Map.of(), "", "java.net.SocketTimeoutException: response timed out"));
        try (var runner = runner()) {
            assertThatThrownBy(() -> runner.run(seed("{\"customerId\":\"000123\",\"accountNumber\":\"000123\"}"), context,
                config(List.of(fixtureStep(call.serviceId()), fixtureStep(call.serviceId())), Map.of("productClass", "gold"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Portable HTTP transport failed").hasMessageContaining("SocketTimeoutException")
                .hasMessageContaining("response timed out");
        }
        assertThat(requests).hasSize(1);
    }

    @Test
    void soapResponsePreservesEmptyAndWhitespaceValues() throws Exception {
        write("create", soapCall("create", "SOAP11"));
        responses.add(response(200, envelope("SOAP11", """
            <c:created xmlns:c="urn:cards"><c:number>000123</c:number><c:empty/><c:spaces>  </c:spaces></c:created>
            """)));
        try (var runner = runner()) {
            WorkItem output = runner.run(seed("{\"customerId\":\"000123\"}"), context,
                config(List.of(step("create")), Map.of("customerId", "parameter")));
            assertThat(new JsonDocumentReader().readObject(output.payload()))
                .containsEntry("cardNumber", "000123").containsEntry("empty", "").containsEntry("spaces", "  ");
        }
    }

    @Test
    void responseFieldsFeedTheNextPortableStepThroughTheSameJourneyWriter() throws Exception {
        write("first", jsonCall("first", "{{ record.customerId }}"));
        write("second", jsonCall("second", "{{ record.value }}:{{ record.unused }}:{{ params.customerId }}"));
        responses.add(response(200, "{\"value\":\"000123\"}"));
        responses.add(response(200, "{\"value\":\"final\"}"));
        try (var runner = runner()) {
            WorkItem result = runner.run(seed("{\"customerId\":\"000001\",\"unused\":\"keep\"}"), context,
                config(List.of(step("first"), step("second")), Map.of("customerId", "parameter")));
            assertThat(requests).extracting(RenderedCall::body)
                .containsExactly("000001", "000123:keep:parameter");
            assertThat(new JsonDocumentReader().readObject(result.payload()))
                .containsEntry("customerId", "000001").containsEntry("unused", "keep").containsEntry("value", "final");
            assertThat(result.steps()).hasSize(3);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"retry", "continue", "extracts"})
    void laterUnsupportedPortableStepFailsBeforeEarlierNativeAuthOrNetwork(String invalid) throws Exception {
        writeNative(true);
        write("portable", jsonCall("portable", ""));
        var retry = new HttpSequenceWorkerConfig.Retry(invalid.equals("retry") ? 2 : 1, 0, 1, 0, List.of());
        var extracts = invalid.equals("extracts")
            ? List.of(new HttpSequenceWorkerConfig.Extract("/value", null, false, "legacy", true)) : List.<HttpSequenceWorkerConfig.Extract>of();
        var portable = new HttpSequenceWorkerConfig.Step("portable", "portable", null, invalid.equals("continue"),
            retry, extracts, List.of());
        try (var runner = runner()) {
            assertThatThrownBy(() -> runner.run(seed("{}"), context,
                config(List.of(step("native"), portable), Map.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Portable HTTP steps require");
        }
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "duplicate", "eval", "syntax", "pointer"})
    void laterPortableContractOrCompilationFailureCannotSendEarlierRequests(String invalid) throws Exception {
        writeNative(true);
        Map<String, Object> call = jsonCall("portable", invalid.equals("eval") ? "{{ eval('1') }}"
            : invalid.equals("syntax") ? "{{ record." : "");
        if (invalid.equals("unknown")) call.put("requires", List.of("unsupported"));
        if (invalid.equals("duplicate")) call.put("requires", List.of("json_path", "json_path"));
        if (invalid.equals("pointer")) {
            call.put("response", Map.of("kind", "JSON", "soapAction", "", "expectedStatus", 200,
                "extract", Map.of("value", "/~2"), "namespaces", Map.of()));
        }
        write("portable", call);
        try (var runner = runner()) {
            assertThatThrownBy(() -> runner.run(seed("{}"), context,
                config(List.of(step("native"), step("portable")), Map.of())))
                .isInstanceOf(RuntimeException.class)
                .satisfies(failure -> assertThat(AuthFailureException.find(failure)).isEmpty());
        }
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"id\":1} {}", "{\"id\":1,\"id\":2}", "[]", "malformed"})
    void invalidJourneyRecordFailsBeforeAnyRequest(String record) throws Exception {
        write("portable", jsonCall("portable", ""));
        try (var runner = runner()) {
            assertThatThrownBy(() -> runner.run(seed(record), context, config(List.of(step("portable")), Map.of())))
                .isInstanceOf(TemplateDataException.class);
        }
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"value\":null}", "{}", "{\"value\":[]}", "{\"value\":\"ok\"} {}", "{\"value\":1,\"value\":2}"})
    void invalidPortableResponseStopsTheJourney(String body) throws Exception {
        write("portable", jsonCall("portable", ""));
        responses.add(response(200, body));
        try (var runner = runner()) {
            assertThatThrownBy(() -> runner.run(seed("{}"), context,
                config(List.of(step("portable"), step("portable")), Map.of())))
                .isInstanceOf(TemplateDataException.class);
        }
        assertThat(requests).hasSize(1);
    }

    @Test
    void unexpectedSuccessfulStatusAndOversizedBodyFailWithoutAnotherAttempt() throws Exception {
        write("portable", jsonCall("portable", ""));
        for (var response : List.of(response(201, "{\"value\":\"ok\"}"),
            response(200, "{\"value\":\"" + "é".repeat(524288) + "\"}"))) {
            responses.add(response);
            try (var runner = runner()) {
                assertThatThrownBy(() -> runner.run(seed("{}"), context,
                    config(List.of(step("portable")), Map.of())))
                    .isInstanceOf(TemplateDataException.class);
            }
        }
        assertThat(requests).hasSize(2);
    }

    @Test
    void malformedUtf8InOtherwiseValidJsonStopsBeforeAnotherRequest() throws Exception {
        write("portable", jsonCall("portable", ""));
        var content = new ByteArrayOutputStream();
        content.writeBytes("{\"value\":\"private-".getBytes(StandardCharsets.UTF_8));
        content.write(0x80);
        content.writeBytes("\"}".getBytes(StandardCharsets.UTF_8));
        var attempts = new AtomicInteger();
        HttpClient client = mock(HttpClient.class);
        when(client.execute(any(ClassicHttpRequest.class), any(HttpClientResponseHandler.class)))
            .thenAnswer(invocation -> {
                attempts.incrementAndGet();
                HttpClientResponseHandler<?> handler = invocation.getArgument(1);
                try (var response = new BasicClassicHttpResponse(200)) {
                    response.setEntity(new InputStreamEntity(new ByteArrayInputStream(content.toByteArray()),
                        -1, ContentType.APPLICATION_JSON));
                    return handler.handleResponse(response);
                }
            });
        try (var runner = runner(new ApacheHttpCallExecutor(client, client))) {
            assertThatThrownBy(() -> runner.run(seed("{}"), context,
                config(List.of(step("portable"), step("portable")), Map.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Portable HTTP transport failed")
                .hasMessageContaining("MalformedInputException").hasMessageNotContaining("private-");
        }
        assertThat(attempts).hasValue(1);
    }

    @Test
    void nativeAliasesExtractsAndSettersRemainOnTheirExistingPath() throws Exception {
        writeNative(false);
        responses.add(response(200, "{\"id\":\"000123\"}"));
        var nativeStep = new HttpSequenceWorkerConfig.Step("native", "native", null, false, null,
            List.of(new HttpSequenceWorkerConfig.Extract("/id", null, false, "result.id", true)),
            List.of(new HttpSequenceWorkerConfig.SetValue("setter", "{{ payload.result.id }}:{{ vars.marker }}", false)));
        try (var runner = runner()) {
            WorkItem output = runner.run(seed("{\"customerId\":\"000001\"}"), context,
                config(List.of(nativeStep), Map.of("marker", "native")));
            assertThat(requests.getFirst().body()).isEqualTo("000001:000001:000001:native");
            assertThat(new JsonDocumentReader().readObject(output.payload()))
                .containsEntry("result", Map.of("id", "000123")).containsEntry("setter", "000123:native");
        }
    }

    @Test
    void nativeExtractsPreserveDuplicateAndOverlappingDestinationOrder() throws Exception {
        writeNative(false);
        responses.add(response(200, "{\"first\":1,\"second\":2,\"last\":3}"));
        var nativeStep = new HttpSequenceWorkerConfig.Step("native", "native", null, false, null,
            List.of(new HttpSequenceWorkerConfig.Extract("/first", null, false, "result.id", true),
                new HttpSequenceWorkerConfig.Extract("/second", null, false, "result", true),
                new HttpSequenceWorkerConfig.Extract("/last", null, false, "result.id", true)), List.of());
        try (var runner = runner()) {
            WorkItem output = runner.run(seed("{\"customerId\":\"000001\"}"), context,
                config(List.of(nativeStep), Map.of("marker", "native")));
            assertThat(new JsonDocumentReader().readObject(output.payload())).containsEntry("result", Map.of("id", 3));
        }
    }

    private HttpSequenceRunner runner() {
        return runner(executor);
    }

    private HttpSequenceRunner runner(HttpCallExecutor transport) {
        var redis = new RedisSequenceProperties();
        redis.setEnabled(false);
        return new HttpSequenceRunner(mapper, Clock.systemUTC(),
            new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE), new TemplateLoader(), transport,
            new DefaultHttpSequenceTargetResolver(), redis);
    }

    private HttpCallResult recordRequest(URI target, RenderedCall request) {
        targets.add(target);
        requests.add(request);
        return responses.removeFirst();
    }

    private WorkItem seed(String body) {
        return WorkItem.text(info, body).contentType("application/json").build();
    }

    private HttpSequenceWorkerConfig config(List<HttpSequenceWorkerConfig.Step> steps, Map<String, Object> params) {
        return new HttpSequenceWorkerConfig("https://sut/base/service", root.toString(), "default", 1, steps,
            new HttpSequenceWorkerConfig.DebugCapture(HttpSequenceWorkerConfig.DebugCaptureMode.NONE,
                0, 1, 1, false, false, 0, 1), params);
    }

    private HttpSequenceWorkerConfig.Step step(String callId) {
        return new HttpSequenceWorkerConfig.Step(callId, callId, null, false, null, List.of(), List.of());
    }

    private HttpSequenceWorkerConfig.Step fixtureStep(String serviceId) {
        return new HttpSequenceWorkerConfig.Step("create", "create", serviceId, false, null, List.of(), List.of());
    }

    private PortableHttpCall loadFixture(String name) throws Exception {
        try (var resource = getClass().getResourceAsStream("/portable/examples/" + name + ".json")) {
            assertThat(resource).as("canonical portable fixture %s", name).isNotNull();
            byte[] document = resource.readAllBytes();
            Files.write(root.resolve(name + ".json"), document);
            return new PortableHttpCallParser().parse(new String(document, StandardCharsets.UTF_8));
        }
    }

    private HttpCallResult response(int status, String body) {
        return new HttpCallResult(status, Map.of(), body, null);
    }

    private Map<String, Object> jsonCall(String callId, String body) {
        return new LinkedHashMap<>(Map.of("profile", "portable-v1", "requires", List.of("json_path"),
            "request", Map.of("serviceId", "default", "callId", callId, "protocol", "HTTP", "method", "POST",
                "pathTemplate", "{{ endpoint.path }}", "bodyTemplate", body, "headersTemplate", Map.of()),
            "response", Map.of("kind", "JSON", "soapAction", "", "expectedStatus", 200,
                "extract", Map.of("value", "/value"), "namespaces", Map.of())));
    }

    private Map<String, Object> soapCall(String callId, String kind) {
        return Map.of("profile", "portable-v1", "requires", List.of("xml_text", "xml_path", kind.toLowerCase()),
            "request", Map.of("serviceId", "default", "callId", callId, "protocol", "HTTP", "method", "POST",
                "pathTemplate", "{{ endpoint.path }}", "bodyTemplate", "<card>{{ xml_text(record.customerId) }}</card>",
                "headersTemplate", Map.of("X-Param", "{{ params.customerId }}")),
            "response", Map.of("kind", kind, "soapAction", "urn:create-card", "expectedStatus", 200,
                "extract", Map.of("cardNumber", "/soap:Envelope/soap:Body/c:created/c:number",
                    "empty", "/soap:Envelope/soap:Body/c:created/c:empty", "spaces", "/soap:Envelope/soap:Body/c:created/c:spaces"),
                "namespaces", Map.of("c", "urn:cards")));
    }

    private String envelope(String kind, String body) {
        String namespace = kind.equals("SOAP11") ? "http://schemas.xmlsoap.org/soap/envelope/"
            : "http://www.w3.org/2003/05/soap-envelope";
        return "<env:Envelope xmlns:env=\"" + namespace + "\"><env:Body>" + body + "</env:Body></env:Envelope>";
    }

    private void write(String callId, Map<String, Object> call) throws Exception {
        Files.writeString(root.resolve(callId + ".json"), mapper.writeValueAsString(call));
    }

    private void writeNative(boolean auth) throws Exception {
        Map<String, Object> definition = new LinkedHashMap<>(Map.of("serviceId", "default", "callId", "native",
            "protocol", "HTTP", "method", "POST", "pathTemplate", "/native",
            "bodyTemplate", "{{ payload.customerId }}:{{ payloadAsJson.customerId }}:{{ ctx.customerId }}:{{ vars.marker }}",
            "headersTemplate", Map.of()));
        if (auth) definition.put("authRef", Map.of("profileId", "missing", "applyAs", "HTTP_AUTHORIZATION_BEARER"));
        write("native", definition);
    }
}
