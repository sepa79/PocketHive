package io.pockethive.processor.mip;

/**
 * Responsibility: expose the owned listener resource release effect.
 * Must not: own configuration or session correlation.
 * Contract: RESP-PROCESSOR-MIP-SESSION — docs/architecture/runtime-responsibilities.md#resp-processor-mip-session.
 */
public interface MipListener extends AutoCloseable {
  @Override void close();
}
