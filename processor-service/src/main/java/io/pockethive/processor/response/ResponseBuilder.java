package io.pockethive.processor.response;

import io.pockethive.work.api.WorkItemBuilder;

import io.pockethive.processor.metrics.CallMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.swarm.model.OutcomeHeaders;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerInfo;
import java.util.Map;

/**
 * Responsibility: construct shared protocol result envelopes from supplied request and outcome values.
 * Must not: provision Work/CP topology or let one protocol handler reinterpret another protocol's result.
 * Contract: RESP-PROCESSOR-EXECUTE — docs/architecture/runtime-responsibilities.md#resp-processor-execute.
 */
public class ResponseBuilder {
  private static final String HEADER_DURATION = OutcomeHeaders.PROCESSOR_DURATION_MS;
  private static final String HEADER_SUCCESS = OutcomeHeaders.PROCESSOR_SUCCESS;
  private static final String HEADER_STATUS = OutcomeHeaders.PROCESSOR_STATUS;
  private static final String HEADER_CONNECTION_LATENCY = "x-ph-processor-connection-latency-ms";

  public static WorkItem build(ObjectNode result, WorkerInfo info, CallMetrics metrics) {
    return build(result, info, metrics, Map.of());
  }

  public static WorkItem build(ObjectNode result, WorkerInfo info, CallMetrics metrics, Map<String, Object> extraStepHeaders) {
    String contentType = responseBody(result).startsWith("<")
        ? "application/xml"
        : "application/json";
    WorkItemBuilder builder = WorkItem.json(info, result)
        .contentType(contentType)
        .stepHeader(HEADER_DURATION, Long.toString(metrics.durationMs()))
        .stepHeader(HEADER_CONNECTION_LATENCY, Long.toString(metrics.connectionLatencyMs()))
        .stepHeader(HEADER_SUCCESS, Boolean.toString(metrics.success()))
        .stepHeader(HEADER_STATUS, Integer.toString(metrics.statusCode()));

    if (extraStepHeaders != null && !extraStepHeaders.isEmpty()) {
      extraStepHeaders.forEach(builder::stepHeader);
    }

    return builder.build();
  }

  private static String responseBody(ObjectNode result) {
    JsonNode directBody = result.path("body");
    if (directBody.isTextual()) {
      return directBody.asText().trim();
    }
    JsonNode outcomeBody = result.path("outcome").path("body");
    if (outcomeBody.isTextual()) {
      return outcomeBody.asText().trim();
    }
    return "";
  }
}
