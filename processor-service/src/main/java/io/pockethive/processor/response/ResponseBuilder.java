package io.pockethive.processor.response;

import io.pockethive.processor.metrics.CallMetrics;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.worker.sdk.api.WorkItem;
import java.util.Map;

public class ResponseBuilder {
  private static final String HEADER_DURATION = "x-ph-processor-duration-ms";
  private static final String HEADER_SUCCESS = "x-ph-processor-success";
  private static final String HEADER_STATUS = "x-ph-processor-status";
  private static final String HEADER_CONNECTION_LATENCY = "x-ph-processor-connection-latency-ms";

  public static WorkItem build(ObjectNode result, String role, CallMetrics metrics) {
    return build(result, role, metrics, Map.of());
  }

  public static WorkItem build(
      ObjectNode result,
      String role,
      CallMetrics metrics,
      Map<String, Object> extraHeaders
  ) {
    WorkItem.Builder builder = WorkItem.json(result)
        .header("content-type", result.has("body") && result.get("body").asText().trim().startsWith("<") ? "application/xml" : "application/json")
        .header("x-ph-service", role)
        .header(HEADER_DURATION, Long.toString(metrics.durationMs()))
        .header(HEADER_CONNECTION_LATENCY, Long.toString(metrics.connectionLatencyMs()))
        .header(HEADER_SUCCESS, Boolean.toString(metrics.success()))
        .header(HEADER_STATUS, Integer.toString(metrics.statusCode()));
    if (extraHeaders != null && !extraHeaders.isEmpty()) {
      extraHeaders.forEach(builder::header);
    }
    return builder.build();
  }
}
