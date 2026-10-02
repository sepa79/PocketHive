package io.pockethive.processor.handler;

import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: carry prepared ISO bytes and explicit client credential transport options.
 * Must not: apply credentials, own connections or infer execution mode.
 * Contract: RESP-PROCESSOR-EXECUTE — docs/architecture/runtime-responsibilities.md#resp-processor-execute.
 */
record Iso8583AuthenticatedPayload(byte[] payload, Map<String, Object> transportOptions) {
  Iso8583AuthenticatedPayload {
    payload = Objects.requireNonNull(payload, "payload").clone();
    transportOptions = Map.copyOf(Objects.requireNonNull(transportOptions, "transportOptions"));
  }

  @Override public byte[] payload() {
    return payload.clone();
  }
}
