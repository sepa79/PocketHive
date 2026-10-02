package io.pockethive.processor.handler;

import java.util.Locale;
import java.util.Objects;

/**
 * Responsibility: define the selected ISO transport frame and its payload limit.
 * Must not: encode ISO fields, choose endpoints or own connection state.
 * Contract: RESP-PROCESSOR-EXECUTE — docs/architecture/runtime-responsibilities.md#resp-processor-execute.
 */
public enum Iso8583WireProfile {
  MC_2BYTE_LEN_BIN_BITMAP;

  public static final int LENGTH_PREFIX_BYTES = 2;
  public static final int MAX_PAYLOAD_BYTES = 65535;

  public static Iso8583WireProfile fromId(String id) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("wireProfileId must not be blank");
    }
    try {
      return valueOf(id.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unsupported ISO8583 wireProfileId: " + id, ex);
    }
  }

  public void validatePayload(byte[] payload) {
    Objects.requireNonNull(payload, "payload");
    if (payload.length > MAX_PAYLOAD_BYTES) {
      throw new IllegalArgumentException("ISO8583 payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
    }
  }

  public byte[] frame(byte[] payload) {
    validatePayload(payload);
    byte[] framed = new byte[LENGTH_PREFIX_BYTES + payload.length];
    framed[0] = (byte) ((payload.length >> 8) & 0xFF);
    framed[1] = (byte) (payload.length & 0xFF);
    System.arraycopy(payload, 0, framed, LENGTH_PREFIX_BYTES, payload.length);
    return framed;
  }

  public String id() {
    return name();
  }

  public int maxPayloadBytes() {
    return MAX_PAYLOAD_BYTES;
  }
}
