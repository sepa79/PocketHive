package io.pockethive.processor.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.iso8583.Iso8583Codec;
import io.pockethive.processor.ProcessorPacer;
import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.exception.ProcessorCallException;
import io.pockethive.processor.metrics.CallMetricsRecorder;
import io.pockethive.processor.mip.MipReply;
import io.pockethive.swarm.model.ResultRules;
import io.pockethive.templating.PebbleTemplateRenderer;
import io.pockethive.templating.api.DisabledSequenceAccess;
import io.pockethive.work.api.Iso8583Request;
import io.pockethive.work.api.Iso8583RequestEnvelope;
import io.pockethive.work.api.IsoSchemaRef;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.auth.AuthApplyAs;
import io.pockethive.worker.sdk.auth.AuthRef;
import io.pockethive.worker.sdk.config.RedisSequenceProperties;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Iso8583ProtocolHandlerTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final WorkerInfo SOURCE = new WorkerInfo("generator", "swarm", "generator-1", null, null);
  private static final WorkerInfo PROCESSOR = new WorkerInfo("processor", "swarm", "processor-1", null, null);
  private final Iso8583Codec codec = new Iso8583Codec();
  private final CallMetricsRecorder metrics = new CallMetricsRecorder();
  private IsoSchemaRef schema;
  private ProcessorWorkerConfig config;
  private WorkerContext context;

  @BeforeEach
  void setUp() throws Exception {
    schema = new IsoSchemaRef(Path.of(getClass().getResource("/iso8583/schema-registry").toURI()).toString(),
        "synthetic", "1", "J8583_XML", "synthetic-j8583.xml");
    config = new ProcessorWorkerConfig("mip://127.0.0.1:6036",
        ProcessorWorkerConfig.Mode.THREAD_COUNT, 1, null,
        ProcessorWorkerConfig.ConnectionReuse.NONE, false, 1500, true, null, Map.of());
    context = mock(WorkerContext.class);
    when(context.info()).thenReturn(PROCESSOR);
  }

  @ParameterizedTest
  @CsvSource({"00,true", "05,false"})
  void extractsDecodedBusinessCodeWithoutConfusingTransportSuccess(String code, String approved) throws Exception {
    byte[] requestBytes = codec.encode(0x0100, Map.of(3, "000000", 11, "000101"), schema);
    byte[] responseBytes = codec.encode(0x0110, Map.of(11, "000101", 39, code), schema);
    var rules = new ResultRules(
        new ResultRules.ValueExtractor(ResultRules.Source.RESPONSE_HEADER, "(..)", Iso8583ResponseHeaders.field(39)),
        "^(00)$", List.of(new ResultRules.DimensionExtractor("response-mti",
            ResultRules.Source.RESPONSE_HEADER, "(.+)", Iso8583ResponseHeaders.MTI)));
    WorkItem request = request(requestBytes, rules, List.of());
    AtomicInteger exchanges = new AtomicInteger();
    try (var handler = handler((payload, selected) -> {
      assertThat(selected).isEqualTo(config);
      assertThat(payload).containsExactly(requestBytes);
      exchanges.incrementAndGet();
      return new MipReply(codec.decode(responseBytes, schema), responseBytes);
    })) {
      WorkItem result = handler.invoke(request, request.asJsonNode(), config, context);
      assertThat(result.asJsonNode().path("kind").asText()).isEqualTo("iso8583.result");
      assertThat(result.asJsonNode().path("request").path("scheme").asText()).isEqualTo("mip");
      assertThat(result.asJsonNode().path("outcome").path("responseHex").asText())
          .isEqualTo(HexFormat.of().withUpperCase().formatHex(responseBytes));
      assertThat(result.stepHeaders())
          .containsEntry("x-ph-processor-success", "true")
          .containsEntry("x-ph-processor-status", "200")
          .containsEntry("x-ph-business-code", code)
          .containsEntry("x-ph-business-success", approved)
          .containsEntry("x-ph-dim-response-mti", "0110");
      assertThat(result.steps()).hasSize(2);
      assertThat(result.messageId()).isEqualTo(request.messageId());
      assertThat(exchanges).hasValue(1);
      assertThat(metrics.totalCalls()).isEqualTo(1);
      assertThat(metrics.successRatio()).isEqualTo(1);
    }
  }

  @Test
  void invalidResultRulesRecordOnlyOneFailedCall() throws Exception {
    byte[] bytes = codec.encode(0x0100, Map.of(11, "000101"), schema);
    byte[] reply = codec.encode(0x0110, Map.of(11, "000101", 39, "00"), schema);
    var rules = new ResultRules(new ResultRules.ValueExtractor(
        ResultRules.Source.RESPONSE_HEADER, "(", Iso8583ResponseHeaders.field(39)), "00", List.of());
    WorkItem request = request(bytes, rules, List.of());
    try (var handler = handler((payload, selected) -> new MipReply(codec.decode(reply, schema), reply))) {
      assertThatThrownBy(() -> handler.invoke(request, request.asJsonNode(), config, context))
          .isInstanceOf(ProcessorCallException.class)
          .hasCauseInstanceOf(java.util.regex.PatternSyntaxException.class);
      assertThat(metrics.totalCalls()).isEqualTo(1);
      assertThat(metrics.successRatio()).isZero();
    }
  }

  @Test
  void refusesClientCertificateApplicationBeforeCallingPlaintextServer() throws Exception {
    byte[] bytes = codec.encode(0x0100, Map.of(11, "000101"), schema);
    WorkItem request = request(bytes, null,
        List.of(new AuthRef("client-certificate", AuthApplyAs.MTLS_CLIENT_CERT, null, null, null)));
    AtomicInteger exchanges = new AtomicInteger();
    try (var handler = handler((payload, selected) -> {
      exchanges.incrementAndGet();
      throw new AssertionError("Server exchange must not run");
    })) {
      assertThatThrownBy(() -> handler.invoke(request, request.asJsonNode(), config, context))
          .isInstanceOf(ProcessorCallException.class)
          .cause().isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("not supported by the plaintext MIP server");
      assertThat(exchanges).hasValue(0);
    }
  }

  @Test
  void clientOnlyCompositionExplicitlyRejectsServerSelection() throws Exception {
    byte[] bytes = codec.encode(0x0100, Map.of(11, "000101"), schema);
    WorkItem request = request(bytes, null, List.of());
    try (var handler = handler(Iso8583ServerExchange.clientOnly())) {
      assertThatThrownBy(() -> handler.invoke(request, request.asJsonNode(), config, context))
          .isInstanceOf(ProcessorCallException.class)
          .cause().isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("MIP server runtime was not configured");
    }
  }

  @Test
  void sharedEnvelopeValidationRejectsUnknownFieldsBeforeServerExchange() throws Exception {
    byte[] bytes = codec.encode(0x0100, Map.of(11, "000101"), schema);
    WorkItem request = request(bytes, null, List.of());
    var invalid = (com.fasterxml.jackson.databind.node.ObjectNode) request.asJsonNode();
    invalid.put("alternateProtocol", "TCP");
    try (var handler = handler((payload, selected) -> {
      throw new AssertionError("Server exchange must not run");
    })) {
      assertThatThrownBy(() -> handler.invoke(request, invalid, config, context))
          .isInstanceOf(ProcessorCallException.class)
          .cause().isInstanceOf(IllegalArgumentException.class)
          .hasMessage("Invalid ISO8583 request envelope");
    }
  }

  private Iso8583ProtocolHandler handler(Iso8583ServerExchange exchange) {
    return new Iso8583ProtocolHandler(MAPPER,
        Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC),
        metrics, new ProcessorPacer(), new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE),
        new RedisSequenceProperties(), exchange);
  }

  private WorkItem request(byte[] bytes, ResultRules rules, List<AuthRef> auth) {
    return WorkItem.json(SOURCE, Iso8583RequestEnvelope.of(new Iso8583Request(
        Iso8583WireProfile.MC_2BYTE_LEN_BIN_BITMAP.id(), "RAW_HEX",
        HexFormat.of().withUpperCase().formatHex(bytes), Map.of(), null, auth), rules))
        .messageId("authorization-correlation-1").build();
  }
}
