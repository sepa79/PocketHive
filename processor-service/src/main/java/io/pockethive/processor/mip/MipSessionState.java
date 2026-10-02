package io.pockethive.processor.mip;

/**
 * Responsibility: name the explicit MIP session lifecycle states.
 * Must not: mutate the lifecycle or infer connection readiness.
 * Contract: RESP-PROCESSOR-MIP-SESSION — docs/architecture/runtime-responsibilities.md#resp-processor-mip-session.
 */
public enum MipSessionState {
  NEW,
  CONNECTED,
  CLOSED
}
