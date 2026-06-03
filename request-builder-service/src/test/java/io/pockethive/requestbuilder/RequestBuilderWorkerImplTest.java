package io.pockethive.requestbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.pockethive.controlplane.spring.WorkerControlPlaneProperties;
import io.pockethive.observability.ObservabilityContext;
import io.pockethive.requesttemplates.TemplateLoader;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.api.WorkerInfo;
import io.pockethive.worker.sdk.auth.AuthApplyAs;
import io.pockethive.worker.sdk.auth.AuthFailureException;
import io.pockethive.worker.sdk.auth.AuthRef;
import io.pockethive.worker.sdk.testing.ControlPlaneTestFixtures;
import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RequestBuilderWorkerImplTest {

  private static final WorkerControlPlaneProperties WORKER_PROPERTIES =
      ControlPlaneTestFixtures.workerProperties("swarm", "request-builder", "instance");
  private static final WorkerInfo SEED_INFO = new WorkerInfo("ingress", "swarm", "instance", null, null);

  private RequestBuilderWorkerProperties properties;
  private TemplateRenderer templateRenderer;

  @BeforeEach
  void setUp() {
    properties = new RequestBuilderWorkerProperties(new ObjectMapper(), WORKER_PROPERTIES);
    templateRenderer = new PebbleTemplateRenderer();
  }

  @Test
  void buildsHttpEnvelopeFromTemplate() throws Exception {
    Path dir = Files.createTempDirectory("templates");
    Files.createDirectories(dir.resolve("default"));
    Path file = dir.resolve("default/simple-call.json");
    Files.writeString(file, """
        {
          "serviceId": "default",
          "callId": "simple",
          "protocol": "HTTP",
          "method": "POST",
          "pathTemplate": "/test",
          "bodyTemplate": "{{ payload }}",
          "headersTemplate": {
            "X-Test": "yes"
          },
	          "resultRules": {
	            "businessCode": {
	              "source": "RESPONSE_BODY",
	              "pattern": "RC=([A-Z0-9]+)"
	            },
	            "successRegex": "^(00)$",
	            "dimensions": [
	              {
	                "name": "segment",
	                "source": "REQUEST_HEADER",
	                "header": "X-Segment",
	                "pattern": "(.+)"
	              }
	            ]
	          }
	        }
	        """);

    properties.setConfig(Map.of(
        "templateRoot", dir.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", true
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);

    WorkItem seed = WorkItem.text(SEED_INFO, "body").header("x-ph-call-id", "simple").build();
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(), "default", true, Map.of());
    WorkerContext context = new TestWorkerContext(config);

    WorkItem result = worker.onMessage(seed, context);

    assertThat(result).isNotNull();
    assertThat(result.contentType()).isEqualTo("application/json");

    JsonNode envelope = new ObjectMapper().readTree(result.asString());
    assertThat(envelope.get("kind").asText()).isEqualTo("http.request");
    assertThat(envelope.get("request").get("path").asText()).isEqualTo("/test");
	    assertThat(envelope.get("request").get("method").asText()).isEqualTo("POST");
	    assertThat(envelope.get("request").get("body").asText()).isEqualTo("body");
	    assertThat(envelope.get("request").get("headers").get("X-Test").asText()).isEqualTo("yes");
	    assertThat(envelope.get("resultRules")).isNotNull();
	    assertThat(envelope.get("resultRules").get("successRegex").asText()).isEqualTo("^(00)$");
	    assertThat(envelope.get("resultRules").get("dimensions")).isNotNull();
	    assertThat(envelope.get("resultRules").get("dimensions").isArray()).isTrue();
	    assertThat(envelope.get("resultRules").get("dimensions").size()).isEqualTo(1);
	    assertThat(envelope.get("resultRules").get("dimensions").get(0).get("name").asText()).isEqualTo("segment");
	  }

  @Test
  void appliesHttpHeaderAndQueryAuthFromAuthProfiles() throws Exception {
    Path dir = Files.createTempDirectory("templates-http-auth");
    Files.createDirectories(dir.resolve("default"));
    Files.writeString(dir.resolve("authProfiles.yaml"), """
        profiles:
          bearer:
            type: STATIC_TOKEN
            storage:
              mode: NONE
            token: header-token
          query:
            type: API_KEY
            storage:
              mode: NONE
            key: query-token
            queryParam: api_key
        """);
    Files.writeString(dir.resolve("default/header-call.yaml"), """
        serviceId: default
        callId: header
        protocol: HTTP
        method: GET
        pathTemplate: /header
        bodyTemplate: ""
        headersTemplate: {}
        authRef:
          profileId: bearer
          applyAs: HTTP_AUTHORIZATION_BEARER
        """);
    Files.writeString(dir.resolve("default/query-call.yaml"), """
        serviceId: default
        callId: query
        protocol: HTTP
        method: GET
        pathTemplate: /query?existing=1
        bodyTemplate: ""
        headersTemplate: {}
        authRef:
          profileId: query
          applyAs: HTTP_QUERY_PARAM
        """);

    properties.setConfig(Map.of(
        "templateRoot", dir.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", true
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(), "default", true, Map.of());
    WorkerContext context = new TestWorkerContext(config);

    WorkItem headerSeed = WorkItem.text(SEED_INFO, "").header("x-ph-call-id", "header").build();
    JsonNode headerEnvelope = new ObjectMapper().readTree(worker.onMessage(headerSeed, context).asString());
    assertThat(headerEnvelope.get("request").get("headers").get("Authorization").asText())
        .isEqualTo("Bearer header-token");

    WorkItem querySeed = WorkItem.text(SEED_INFO, "").header("x-ph-call-id", "query").build();
    JsonNode queryEnvelope = new ObjectMapper().readTree(worker.onMessage(querySeed, context).asString());
    assertThat(queryEnvelope.get("request").get("path").asText())
        .isEqualTo("/query?existing=1&api_key=query-token");
  }

  @Test
  void appliesHttpAuthProfileUsingSutContext() throws Exception {
    Path dir = Files.createTempDirectory("templates-http-auth-sut");
    Files.createDirectories(dir.resolve("default"));
    Files.writeString(dir.resolve("authProfiles.yaml"), """
        profiles:
          bearer:
            type: STATIC_TOKEN
            storage:
              mode: NONE
            token: "{{ sut.endpoints['default'].baseUrl }}/oauth/token"
        """);
    Files.writeString(dir.resolve("default/header-call.yaml"), """
        serviceId: default
        callId: header
        protocol: HTTP
        method: GET
        pathTemplate: /header
        bodyTemplate: ""
        headersTemplate: {}
        authRef:
          profileId: bearer
          applyAs: HTTP_AUTHORIZATION_BEARER
        """);

    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(),
        "default",
        true,
        Map.of(),
        Map.of("authProfile", Map.of("sut", Map.of(
            "id", "wiremock-local",
            "endpoints", Map.of("default", Map.of("baseUrl", "http://wiremock:8080"))))));
    WorkerContext context = new TestWorkerContext(config);

    WorkItem seed = WorkItem.text(SEED_INFO, "").header("x-ph-call-id", "header").build();
    JsonNode envelope = new ObjectMapper().readTree(worker.onMessage(seed, context).asString());

    assertThat(envelope.get("request").get("headers").get("Authorization").asText())
        .isEqualTo("Bearer http://wiremock:8080/oauth/token");
  }

  @Test
  void authFailuresThrowOnceThenDropRepeatedFailures() throws Exception {
    Path dir = Files.createTempDirectory("templates-http-auth-failure");
    Files.createDirectories(dir.resolve("default"));
    Files.writeString(dir.resolve("authProfiles.yaml"), """
        profiles:
          bad-query:
            type: STATIC_TOKEN
            storage:
              mode: NONE
            token: failure-token-that-must-not-be-logged
        """);
    Files.writeString(dir.resolve("default/bad-query-auth.yaml"), """
        serviceId: default
        callId: bad-query-auth
        protocol: HTTP
        method: POST
        pathTemplate: /should-not-send
        bodyTemplate: "{{ payload }}"
        headersTemplate: {}
        authRef:
          profileId: bad-query
          applyAs: HTTP_QUERY_PARAM
        """);

    properties.setConfig(Map.of(
        "templateRoot", dir.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", false
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(), "default", false, Map.of());
    WorkerContext context = new TestWorkerContext(config);
    WorkItem seed = WorkItem.text(SEED_INFO, "{}").header("x-ph-call-id", "bad-query-auth").build();

    assertThatThrownBy(() -> worker.onMessage(seed, context))
        .isInstanceOf(AuthFailureException.class)
        .hasMessageContaining("HTTP_QUERY_PARAM auth requires");
    assertThat(worker.onMessage(seed, context)).isNull();
  }

  @Test
  void dropsMessageWhenCallIdMissingAndPassThroughDisabled() {
    Path dir = Path.of("does-not-matter");
    properties.setConfig(Map.of(
        "templateRoot", dir.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", false
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);

    WorkItem seed = WorkItem.text(SEED_INFO, "body").build();
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(), "default", false, Map.of());
    WorkerContext context = new TestWorkerContext(config);

    WorkItem result = worker.onMessage(seed, context);

    assertThat(result).isNull();
  }

  @Test
  void passesThroughWhenTemplateMissingAndPassThroughEnabled() throws Exception {
    Path dir = Files.createTempDirectory("templates-missing");
    // Intentionally do not create any template files.
    properties.setConfig(Map.of(
        "templateRoot", dir.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", true
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);

    WorkItem seed = WorkItem.text(SEED_INFO, "body").header("x-ph-call-id", "unknown").build();
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(), "default", true, Map.of());
    WorkerContext context = new TestWorkerContext(config);

    WorkItem result = worker.onMessage(seed, context);

    assertThat(result).isSameAs(seed);
  }

  @Test
  void buildsTcpEnvelopeFromTemplate() throws Exception {
    Path dir = Files.createTempDirectory("tcp-templates");
    Files.createDirectories(dir.resolve("default"));
    Path file = dir.resolve("default/tcp-call.json");
    Files.writeString(file, """
        {
          "serviceId": "default",
          "callId": "tcp-test",
          "protocol": "TCP",
          "behavior": "REQUEST_RESPONSE",
          "bodyTemplate": "{{ payload }}",
          "headersTemplate": {},
          "endTag": "</Document>",
          "maxBytes": 8192
        }
        """);

    properties.setConfig(Map.of(
        "templateRoot", dir.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", true
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);

    WorkItem seed = WorkItem.text(SEED_INFO, "test-data").header("x-ph-call-id", "tcp-test").build();
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(), "default", true, Map.of());
    WorkerContext context = new TestWorkerContext(config);

    WorkItem result = worker.onMessage(seed, context);

    assertThat(result).isNotNull();
    JsonNode envelope = new ObjectMapper().readTree(result.asString());
    assertThat(envelope.get("kind").asText()).isEqualTo("tcp.request");
    assertThat(envelope.get("request").get("behavior").asText()).isEqualTo("REQUEST_RESPONSE");
    assertThat(envelope.get("request").get("body").asText()).isEqualTo("test-data");
    assertThat(envelope.get("request").get("endTag").asText()).isEqualTo("</Document>");
    assertThat(envelope.get("request").get("maxBytes").asInt()).isEqualTo(8192);
  }

  @Test
  void buildsTcpEnvelopeWithPayloadAuthAndProcessorStageMetadata() throws Exception {
    Path dir = Files.createTempDirectory("tcp-templates-auth");
    Files.createDirectories(dir.resolve("default"));
    Files.writeString(dir.resolve("authProfiles.yaml"), """
        profiles:
          prefix:
            type: MESSAGE_FIELD_AUTH
            storage:
              mode: NONE
            value: "AUTH:"
          mtls:
            type: TLS_CLIENT_CERT
            storage:
              mode: NONE
            keyStorePath: /certs/client.p12
            keyStorePassword: changeit
        """);
    Files.writeString(dir.resolve("default/tcp-prefix.yaml"), """
        serviceId: default
        callId: tcp-prefix
        protocol: TCP
        behavior: REQUEST_RESPONSE
        bodyTemplate: "{{ payload }}"
        headersTemplate: {}
        endTag: "</Document>"
        maxBytes: 8192
        authRef:
          profileId: prefix
          applyAs: TCP_PAYLOAD_PREFIX
        """);
    Files.writeString(dir.resolve("default/tcp-mtls.yaml"), """
        serviceId: default
        callId: tcp-mtls
        protocol: TCP
        behavior: REQUEST_RESPONSE
        bodyTemplate: "{{ payload }}"
        headersTemplate: {}
        maxBytes: 8192
        authRef:
          profileId: mtls
          applyAs: MTLS_CLIENT_CERT
        """);

    properties.setConfig(Map.of(
        "templateRoot", dir.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", true
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(), "default", true, Map.of());
    WorkerContext context = new TestWorkerContext(config);

    WorkItem prefixSeed = WorkItem.text(SEED_INFO, "payload").header("x-ph-call-id", "tcp-prefix").build();
    JsonNode prefixEnvelope = new ObjectMapper().readTree(worker.onMessage(prefixSeed, context).asString());
    assertThat(prefixEnvelope.get("request").get("body").asText()).isEqualTo("AUTH:payload");
    assertThat(prefixEnvelope.get("request").get("authApplications")).isEmpty();

    WorkItem mtlsSeed = WorkItem.text(SEED_INFO, "payload").header("x-ph-call-id", "tcp-mtls").build();
    JsonNode mtlsEnvelope = new ObjectMapper().readTree(worker.onMessage(mtlsSeed, context).asString());
    assertThat(mtlsEnvelope.get("request").get("body").asText()).isEqualTo("payload");
    assertThat(mtlsEnvelope.get("request").get("authApplications")).hasSize(1);
    assertThat(mtlsEnvelope.get("request").get("authApplications").get(0).get("profileId").asText())
        .isEqualTo("mtls");
    assertThat(mtlsEnvelope.get("request").get("authApplications").get(0).get("applyAs").asText())
        .isEqualTo("MTLS_CLIENT_CERT");
  }

  @Test
  void buildsTcpEnvelopeWithoutEndTag() throws Exception {
    Path dir = Files.createTempDirectory("tcp-templates-no-endtag");
    Files.createDirectories(dir.resolve("default"));
    Path file = dir.resolve("default/tcp-streaming.json");
    Files.writeString(file, """
        {
          "serviceId": "default",
          "callId": "tcp-streaming",
          "protocol": "TCP",
          "behavior": "STREAMING",
          "bodyTemplate": "{{ payload }}",
          "headersTemplate": {},
          "maxBytes": 1024
        }
        """);

    properties.setConfig(Map.of(
        "templateRoot", dir.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", true
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);

    WorkItem seed = WorkItem.text(SEED_INFO, "stream-data").header("x-ph-call-id", "tcp-streaming").build();
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(), "default", true, Map.of());
    WorkerContext context = new TestWorkerContext(config);

    WorkItem result = worker.onMessage(seed, context);

    assertThat(result).isNotNull();
    JsonNode envelope = new ObjectMapper().readTree(result.asString());
    assertThat(envelope.get("kind").asText()).isEqualTo("tcp.request");
    assertThat(envelope.get("request").get("behavior").asText()).isEqualTo("STREAMING");
    assertThat(envelope.get("request").get("body").asText()).isEqualTo("stream-data");
    assertThat(envelope.get("request").get("maxBytes").asInt()).isEqualTo(1024);
    // endTag is optional and remains null when not configured
    assertThat(envelope.get("request").path("endTag").isNull()).isTrue();
  }

  @Test
  void buildsIso8583EnvelopeAndCompilesFieldListXmlToRawHex() throws Exception {
    Path dir = Files.createTempDirectory("iso-templates");
    Files.createDirectories(dir.resolve("default"));

    Path schemaRoot = dir.resolve("iso-schemas");
    Path schemaDir = schemaRoot.resolve("ctap-belgium-auth").resolve("1.0.0");
    Files.createDirectories(schemaDir);
    Files.writeString(schemaDir.resolve("ctap-j8583.xml"), """
        <j8583-config>
          <parse type="0100">
            <field num="2" type="LLVAR"/>
            <field num="3" type="NUMERIC" length="6"/>
          </parse>
        </j8583-config>
        """);

    Files.writeString(dir.resolve("default/ctap-iso.json"), """
        {
          "serviceId": "default",
          "callId": "ctap-iso",
          "protocol": "ISO8583",
          "wireProfileId": "MC_2BYTE_LEN_BIN_BITMAP",
          "payloadAdapter": "FIELD_LIST_XML",
          "bodyTemplate": "<iso8583 mti=\\"0100\\"><field num=\\"2\\" value=\\"5554213493338337\\"/><field num=\\"3\\" value=\\"000000\\"/></iso8583>",
          "headersTemplate": {
            "x-iso-flow": "ctap"
          },
          "schemaRef": {
            "schemaRegistryRoot": "%s",
            "schemaId": "ctap-belgium-auth",
            "schemaVersion": "1.0.0",
            "schemaAdapter": "J8583_XML",
            "schemaFile": "ctap-j8583.xml"
          }
        }
        """.formatted(schemaRoot.toString().replace("\\", "\\\\")));

    properties.setConfig(Map.of(
        "templateRoot", dir.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", true
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);

    WorkItem seed = WorkItem.text(SEED_INFO, "unused").header("x-ph-call-id", "ctap-iso").build();
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(), "default", true, Map.of());
    WorkerContext context = new TestWorkerContext(config);

    WorkItem result = worker.onMessage(seed, context);

    assertThat(result).isNotNull();
    JsonNode envelope = new ObjectMapper().readTree(result.asString());
    assertThat(envelope.get("kind").asText()).isEqualTo("iso8583.request");
    assertThat(envelope.get("request").get("wireProfileId").asText()).isEqualTo("MC_2BYTE_LEN_BIN_BITMAP");
    assertThat(envelope.get("request").get("payloadAdapter").asText()).isEqualTo("RAW_HEX");
    assertThat(envelope.get("request").get("payload").asText())
        .isEqualTo("303130306000000000000000313635353534323133343933333338333337303030303030");
    assertThat(envelope.get("request").get("headers").get("x-iso-flow").asText()).isEqualTo("ctap");
    assertThat(envelope.get("request").path("schemaRef").isNull()).isTrue();
  }

  @Test
  void failsWhenIso8583TemplateUsesUnsupportedPayloadAdapter() throws Exception {
    Path dir = Files.createTempDirectory("iso-templates-invalid");
    Files.createDirectories(dir.resolve("default"));
    Files.writeString(dir.resolve("default/ctap-iso.json"), """
        {
          "serviceId": "default",
          "callId": "ctap-iso",
          "protocol": "ISO8583",
          "wireProfileId": "MC_2BYTE_LEN_BIN_BITMAP",
          "payloadAdapter": "UNKNOWN_ADAPTER",
          "bodyTemplate": "<iso8583 mti=\\"0100\\"/>",
          "headersTemplate": {}
        }
        """);

    properties.setConfig(Map.of(
        "templateRoot", dir.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", true
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);

    WorkItem seed = WorkItem.text(SEED_INFO, "unused").header("x-ph-call-id", "ctap-iso").build();
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(), "default", true, Map.of());

    assertThatThrownBy(() -> worker.onMessage(seed, new TestWorkerContext(config)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Request Builder runtime failure");
  }

  @Test
  void reloadsTemplatesWhenConfigChanges() throws Exception {
    Path dir1 = Files.createTempDirectory("templates-1");
    Files.createDirectories(dir1.resolve("default"));
    Files.writeString(dir1.resolve("default/simple-call.json"), """
        {
          "serviceId": "default",
          "callId": "simple",
          "protocol": "HTTP",
          "method": "POST",
          "pathTemplate": "/one",
          "bodyTemplate": "{{ payload }}",
          "headersTemplate": {}
        }
        """);

    Path dir2 = Files.createTempDirectory("templates-2");
    Files.createDirectories(dir2.resolve("default"));
    Files.writeString(dir2.resolve("default/simple-call.json"), """
        {
          "serviceId": "default",
          "callId": "simple",
          "protocol": "HTTP",
          "method": "POST",
          "pathTemplate": "/two",
          "bodyTemplate": "{{ payload }}",
          "headersTemplate": {}
        }
        """);

    // Default config points at dir1.
    properties.setConfig(Map.of(
        "templateRoot", dir1.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", true
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);

    WorkItem seed = WorkItem.text(SEED_INFO, "body").header("x-ph-call-id", "simple").build();

    // First call uses dir1 config.
    RequestBuilderWorkerConfig config1 = new RequestBuilderWorkerConfig(
        dir1.toString(), "default", true, Map.of());
    WorkerContext ctx1 = new TestWorkerContext(config1);
    WorkItem result1 = worker.onMessage(seed, ctx1);
    JsonNode envelope1 = new ObjectMapper().readTree(result1.asString());
    assertThat(envelope1.get("request").get("path").asText()).isEqualTo("/one");

    // Second call supplies a control-plane override pointing at dir2; worker should reload.
    RequestBuilderWorkerConfig config2 = new RequestBuilderWorkerConfig(
        dir2.toString(), "default", true, Map.of());
    WorkerContext ctx2 = new TestWorkerContext(config2);
    WorkItem result2 = worker.onMessage(seed, ctx2);
    JsonNode envelope2 = new ObjectMapper().readTree(result2.asString());
    assertThat(envelope2.get("request").get("path").asText()).isEqualTo("/two");
  }

  @Test
  void throwsWhenTemplateRenderingProducesInvalidEnvelope() throws Exception {
    Path dir = Files.createTempDirectory("templates-invalid");
    Files.createDirectories(dir.resolve("default"));
    Files.writeString(dir.resolve("default/invalid-call.json"), """
        {
          "serviceId": "default",
          "callId": "invalid",
          "protocol": "HTTP",
          "method": "POST",
          "pathTemplate": "   ",
          "bodyTemplate": "{{ payload }}",
          "headersTemplate": {}
        }
        """);

    properties.setConfig(Map.of(
        "templateRoot", dir.toString(),
        "serviceId", "default",
        "passThroughOnMissingTemplate", true
    ));
    RequestBuilderWorkerImpl worker =
        new RequestBuilderWorkerImpl(properties, templateRenderer, new TemplateLoader(), null);

    WorkItem seed = WorkItem.text(SEED_INFO, "body").header("x-ph-call-id", "invalid").build();
    RequestBuilderWorkerConfig config = new RequestBuilderWorkerConfig(
        dir.toString(), "default", true, Map.of());

    assertThatThrownBy(() -> worker.onMessage(seed, new TestWorkerContext(config)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Request Builder runtime failure");
  }

  private static final class TestWorkerContext implements WorkerContext {

    private final RequestBuilderWorkerConfig config;
    private final WorkerInfo info = new WorkerInfo(
        "request-builder",
        WORKER_PROPERTIES.getSwarmId(),
        WORKER_PROPERTIES.getInstanceId(),
        ControlPlaneTestFixtures.workerQueue(WORKER_PROPERTIES.getSwarmId(), "request-builder"),
        null
    );

    private TestWorkerContext(RequestBuilderWorkerConfig config) {
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
      if (config != null && type.isAssignableFrom(RequestBuilderWorkerConfig.class)) {
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
      return LoggerFactory.getLogger("test");
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
    public ObservabilityContext observabilityContext() {
      return new ObservabilityContext();
    }
  }
}
