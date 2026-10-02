package io.pockethive.processor.handler;

import io.pockethive.iso8583.Iso8583Message;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Responsibility: project decoded ISO response fields into existing result-rule header inputs.
 * Must not: parse bytes, mutate fields or decide authorization success.
 * Contract: RESP-PROCESSOR-EXECUTE — docs/architecture/runtime-responsibilities.md#resp-processor-execute.
 */
public final class Iso8583ResponseHeaders {
  public static final String MTI = "iso8583.mti";
  private static final String FIELD_PREFIX = "iso8583.de.";

  private Iso8583ResponseHeaders() {
  }

  public static String field(int number) {
    return FIELD_PREFIX + number;
  }

  public static Map<String, String> project(Iso8583Message message) {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put(MTI, String.format("%04X", message.mti()));
    message.fields().forEach((number, value) -> headers.put(field(number), value));
    return Map.copyOf(headers);
  }
}
