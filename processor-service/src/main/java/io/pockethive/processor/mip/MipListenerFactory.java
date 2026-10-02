package io.pockethive.processor.mip;

/**
 * Responsibility: define the explicit MIP listener binding port.
 * Must not: infer adapters, mutate configuration or settle requests.
 * Contract: RESP-PROCESSOR-MIP-CONFIG — docs/architecture/runtime-responsibilities.md#resp-processor-mip-config.
 */
@FunctionalInterface
public interface MipListenerFactory {
  MipListener bind(MipServerConfig config, MipChannelInitializer pipeline) throws Exception;
}
