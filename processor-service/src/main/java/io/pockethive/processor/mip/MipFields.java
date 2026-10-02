package io.pockethive.processor.mip;

/**
 * Responsibility: name the ISO fields used by the MIP exchange contract.
 * Must not: declare scheme field types, layouts or parsing behavior.
 * Contract: RESP-PROCESSOR-MIP-SESSION — docs/architecture/runtime-responsibilities.md#resp-processor-mip-session.
 */
public final class MipFields {
  public static final int STAN = 11;
  public static final int RESPONSE_CODE = 39;
  public static final int NETWORK_CODE = 70;
  private MipFields() { }
}
