package io.pockethive.httpsequence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.observability.HttpHeaderRedactor;
import io.pockethive.work.api.WorkerInfo;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * Responsibility: project the compatible HTTP Sequence debug capture key and JSON.
 * Must not: open Redis resources, select captures or implement header redaction policy.
 * Contract: RESP-HTTP-SEQUENCE-DEBUG-CAPTURE - docs/architecture/runtime-responsibilities.md#resp-http-sequence-debug-capture.
 */
final class HttpSequenceDebugCapture {
    private final ObjectMapper mapper;

    HttpSequenceDebugCapture(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    static String key(WorkerInfo info) {
        return "ph:debug:http-seq:%s:%s:%s:%s".formatted(
            info.swarmId(), info.role(), info.instanceId(), UUID.randomUUID());
    }

    String project(HttpSequenceTargetResolver.ResolvedTarget target,
                   String serviceId, String callId,
                   HttpCallExecutor.RenderedCall request,
                   HttpCallExecutor.HttpCallResult result,
                   HttpSequenceWorkerConfig.DebugCapture capture) {
      ObjectNode node = mapper.createObjectNode();
      node.put("serviceId", serviceId);
      node.put("callId", callId);
      node.put("status", result.statusCode());
      node.put("targetSource", target.source().name());
      if (target.sutEndpointId() != null) {
        node.put("sutEndpointId", target.sutEndpointId());
      }
      if (result.error() != null) {
        node.put("error", result.error());
      }
      if (capture.includeHeaders()) {
        node.set("headers", mapper.valueToTree(HttpHeaderRedactor.redactValues(result.headers())));
      }

      if (capture.includeRequest() && request != null) {
        ObjectNode req = mapper.createObjectNode();
        req.put("method", request.method());
        req.put("url", target.uri().toString());
        req.set("headers", mapper.valueToTree(HttpHeaderRedactor.redact(request.headers())));
        String requestBody = request.body() == null ? "" : request.body();
        req.put("body", truncateUtf8(requestBody, capture.maxBodyBytes()));
        node.set("request", req);
      }

      String body = result.body() == null ? "" : result.body();
      node.put("body", truncateUtf8(body, capture.maxBodyBytes()));
      return node.toString();
    }

    private static String truncateUtf8(String value, int maxBytes) {
      if (value == null || value.isEmpty() || maxBytes <= 0) {
        return "";
      }
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      if (bytes.length <= maxBytes) {
        return value;
      }
      return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8);
    }
}
