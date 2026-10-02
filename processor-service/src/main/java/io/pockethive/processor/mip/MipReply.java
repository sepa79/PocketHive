package io.pockethive.processor.mip;

import io.pockethive.iso8583.Iso8583Message;
import java.util.Objects;

/**
 * Responsibility: expose an accepted decoded response with its immutable original ISO payload.
 * Must not: infer business approval, reconstruct response fields or mutate session state.
 * Contract: RESP-PROCESSOR-MIP-SESSION — docs/architecture/runtime-responsibilities.md#resp-processor-mip-session.
 */
public record MipReply(Iso8583Message decoded, byte[] payload) {
  public MipReply {
    Objects.requireNonNull(decoded, "decoded");
    payload = Objects.requireNonNull(payload, "payload").clone();
  }

  @Override
  public byte[] payload() {
    return payload.clone();
  }
}
