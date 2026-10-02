package io.pockethive.processor.mip;

import io.pockethive.processor.handler.Iso8583Endpoint;

/**
 * Responsibility: carry one resolved MIP bind target and its required settings.
 * Must not: reconstruct worker configuration or mutate a session.
 * Contract: RESP-PROCESSOR-MIP-CONFIG — docs/architecture/runtime-responsibilities.md#resp-processor-mip-config.
 */
public record MipServerConfig(Iso8583Endpoint endpoint, MipServerSettings settings) {
  public MipServerConfig {
    if (endpoint == null || !endpoint.mipServer()) throw new IllegalArgumentException("MIP endpoint required");
    if (settings == null) throw new IllegalArgumentException("MIP settings required");
  }
}
