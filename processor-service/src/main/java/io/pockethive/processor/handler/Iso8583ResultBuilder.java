package io.pockethive.processor.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.processor.ResultRulesExtractor;
import io.pockethive.processor.metrics.CallMetrics;
import io.pockethive.processor.response.ResponseBuilder;
import io.pockethive.work.api.Iso8583Metrics;
import io.pockethive.work.api.Iso8583Outcome;
import io.pockethive.work.api.Iso8583RequestEnvelope;
import io.pockethive.work.api.Iso8583RequestInfo;
import io.pockethive.work.api.Iso8583ResultEnvelope;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Responsibility: construct the one ISO result observation and apply canonical result rules for both execution modes.
 * Must not: perform network effects, parse ISO fields or infer business success separately.
 * Contract: RESP-PROCESSOR-EXECUTE — docs/architecture/runtime-responsibilities.md#resp-processor-execute.
 */
final class Iso8583ResultBuilder {
  static final String TRANSPORT = "iso8583";
  static final int RESPONSE_STATUS = 200;
  private static final String SEND = "SEND";
  private final ObjectMapper mapper;

  Iso8583ResultBuilder(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  WorkItem build(WorkItem message, Iso8583RequestEnvelope request, Iso8583Endpoint endpoint,
                 Iso8583WireProfile profile, int payloadBytes, byte[] response,
                 Map<String, String> responseHeaders, CallMetrics metrics, WorkerInfo info) {
    String responseHex = HexFormat.of().withUpperCase().formatHex(response);
    Iso8583ResultEnvelope result = Iso8583ResultEnvelope.of(
        new Iso8583RequestInfo(TRANSPORT, endpoint.scheme(), SEND, endpoint.endpoint(),
            profile.id(), request.request().payloadAdapter(), payloadBytes),
        new Iso8583Outcome(Iso8583ResultEnvelope.OUTCOME_ISO8583_RESPONSE, RESPONSE_STATUS, responseHex, null),
        new Iso8583Metrics(metrics.durationMs(), metrics.connectionLatencyMs()));
    ObjectNode encoded = mapper.valueToTree(result);
    Map<String, Object> extracted = ResultRulesExtractor.extract(request.resultRules(),
        request.request().payload(), request.request().headers(), responseHex, responseHeaders);
    WorkItem step = ResponseBuilder.build(encoded, info, metrics, extracted);
    WorkItem updated = message.addStep(info, step.asString(), step.stepHeaders());
    return updated.toBuilder().contentType(step.contentType()).build();
  }

  Map<String, Object> requestMetadata(Iso8583Endpoint endpoint, Iso8583RequestEnvelope request) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("transport", TRANSPORT);
    metadata.put("endpoint", endpoint.endpoint());
    metadata.put("scheme", endpoint.scheme());
    metadata.put("payloadAdapter", request.request().payloadAdapter());
    metadata.put("wireProfileId", request.request().wireProfileId());
    return Map.copyOf(metadata);
  }
}
