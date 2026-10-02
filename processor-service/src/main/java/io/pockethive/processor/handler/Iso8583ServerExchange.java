package io.pockethive.processor.handler;

import io.pockethive.processor.ProcessorWorkerConfig;
import io.pockethive.processor.mip.MipReply;

/**
 * Responsibility: expose explicitly composed accepted-session ISO byte execution.
 * Must not: own session state, parse shared envelopes or construct Work outcomes.
 * Contract: RESP-PROCESSOR-MIP-SESSION — docs/architecture/runtime-responsibilities.md#resp-processor-mip-session.
 */
@FunctionalInterface
public interface Iso8583ServerExchange {
  MipReply exchange(byte[] payload, ProcessorWorkerConfig config) throws Exception;

  static Iso8583ServerExchange clientOnly() {
    return (payload, config) -> {
      throw new IllegalStateException("MIP server runtime was not configured for this processor");
    };
  }
}
