package io.pockethive.worker.runtime;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Objects;

/**
 * Canonical representation of worker-emitted control-plane messages.
 *
 * <p>The envelope exposes the common metadata fields while keeping the protocol-specific
 * payload opaque so services can attach arbitrary status/configuration structures without the
 * shared runtime needing to understand them.</p>
 */
public record WorkerMessageEnvelope(String messageId,
                                     Instant timestamp,
                                     String event,
                                     String kind,
                                     String version,
                                     String role,
                                     String instance,
                                     String swarmId,
                                     String location,
                                     String origin,
                                     String correlationId,
                                     String idempotencyKey,
                                     ObjectNode payload) {

  public WorkerMessageEnvelope {
    messageId = requireText(messageId, "messageId");
    timestamp = Objects.requireNonNull(timestamp, "timestamp");
    payload = payload == null ? JsonNodeFactory.instance.objectNode() : payload.deepCopy();
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be null or blank");
    }
    return value;
  }
}
