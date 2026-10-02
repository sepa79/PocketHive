package io.pockethive.processor.mip;

import java.util.Set;

/**
 * Responsibility: define the four supported MIP message types and their request/response pairs.
 * Must not: parse ISO fields, construct messages or own session state.
 * Contract: RESP-PROCESSOR-MIP-SESSION — docs/architecture/runtime-responsibilities.md#resp-processor-mip-session.
 */
public enum MipMessageType {
  AUTHORIZATION_REQUEST(0x0100),
  AUTHORIZATION_RESPONSE(0x0110),
  NETWORK_REQUEST(0x0800),
  NETWORK_RESPONSE(0x0810);

  private final int value;

  MipMessageType(int value) {
    this.value = value;
  }

  public int value() {
    return value;
  }

  /** Required exchange fields; schema field types remain owned by the external pack. */
  public Set<Integer> requiredFields() {
    return switch (this) {
      case AUTHORIZATION_REQUEST -> Set.of(MipFields.STAN);
      case AUTHORIZATION_RESPONSE -> Set.of(MipFields.STAN, MipFields.RESPONSE_CODE);
      case NETWORK_REQUEST -> Set.of(MipFields.STAN, MipFields.NETWORK_CODE);
      case NETWORK_RESPONSE -> Set.of(MipFields.STAN, MipFields.RESPONSE_CODE, MipFields.NETWORK_CODE);
    };
  }

  public MipMessageType expectedResponse() {
    return switch (this) {
      case AUTHORIZATION_REQUEST -> AUTHORIZATION_RESPONSE;
      case NETWORK_REQUEST -> NETWORK_RESPONSE;
      case AUTHORIZATION_RESPONSE, NETWORK_RESPONSE ->
          throw new IllegalArgumentException("Only MIP request message types can be exchanged");
    };
  }

  public static MipMessageType fromValue(int value) {
    for (MipMessageType type : values()) {
      if (type.value == value) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unsupported MIP message type: " + Integer.toHexString(value));
  }
}
