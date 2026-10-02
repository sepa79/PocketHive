package io.pockethive.iso8583;

import java.util.Map;

/**
 * Responsibility: carry a decoded ISO8583 message with its exact formatted wire field values.
 * Must not: own schema parsing, session state or transaction outcomes.
 * Contract: RESP-ISO8583-CODEC — docs/architecture/runtime-responsibilities.md#resp-iso8583-codec.
 */
public record Iso8583Message(int mti, Map<Integer, String> fields) {
  public Iso8583Message {
    fields = Map.copyOf(fields);
  }

  public String field(int number) {
    return fields.get(number);
  }
}
