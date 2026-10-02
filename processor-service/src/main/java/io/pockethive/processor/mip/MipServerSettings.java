package io.pockethive.processor.mip;

import io.pockethive.work.api.IsoSchemaRef;
import java.util.Objects;

/**
 * Responsibility: carry the required local MIP adapter settings.
 * Must not: supply adapter defaults, open sockets or resolve schema paths.
 * Contract: RESP-PROCESSOR-MIP-CONFIG — docs/architecture/runtime-responsibilities.md#resp-processor-mip-config.
 */
public record MipServerSettings(IsoSchemaRef schemaRef, int maxPending, MipNetworkLayout networkManagement) {
  public MipServerSettings {
    Objects.requireNonNull(schemaRef, "schemaRef");
    Objects.requireNonNull(networkManagement, "networkManagement");
    MipSession.validateCapacity(maxPending);
  }
}
