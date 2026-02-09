package io.pockethive.processor.response;

import io.pockethive.processor.metrics.CallMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerInfo;

public class ResponseBuilder {
  private static final String HEADER_DURATION = "x-ph-processor-duration-ms";
  private static final String HEADER_SUCCESS = "x-ph-processor-success";
  private static final String HEADER_STATUS = "x-ph-processor-status";
  private static final String HEADER_CONNECTION_LATENCY = "x-ph-processor-connection-latency-ms";

  public static WorkItem build(ObjectNode result, WorkerInfo info, CallMetrics metrics) {
    String contentType = responseBody(result).startsWith("<")
        ? "application/xml"
        : "application/json";
    return WorkItem.json(info, result)
        .contentType(contentType)
        .stepHeader(HEADER_DURATION, Long.toString(metrics.durationMs()))
        .stepHeader(HEADER_CONNECTION_LATENCY, Long.toString(metrics.connectionLatencyMs()))
        .stepHeader(HEADER_SUCCESS, Boolean.toString(metrics.success()))
        .stepHeader(HEADER_STATUS, Integer.toString(metrics.statusCode()))
        .build();
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
