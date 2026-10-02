package io.pockethive.processor.handler;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.pockethive.work.api.Iso8583Request;
import io.pockethive.work.api.Iso8583RequestEnvelope;
import java.util.HexFormat;

/**
 * Responsibility: decode the shared ISO request envelope and its selected raw byte payload.
 * Must not: parse ISO field schemas, apply credentials or execute network effects.
 * Contract: RESP-PROCESSOR-EXECUTE — docs/architecture/runtime-responsibilities.md#resp-processor-execute.
 */
final class Iso8583EnvelopeCodec {
  private static final String RAW_HEX = "RAW_HEX";
  private final ObjectReader envelopeReader;

  Iso8583EnvelopeCodec(ObjectMapper mapper) {
    envelopeReader = mapper.readerFor(Iso8583RequestEnvelope.class)
        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  }

  Iso8583RequestEnvelope decodeEnvelope(JsonNode envelope) {
    try {
      return envelopeReader.readValue(envelope);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid ISO8583 request envelope", ex);
    }
  }

  byte[] decodePayload(Iso8583Request request) {
    if (!RAW_HEX.equals(request.payloadAdapter())) {
      throw new IllegalArgumentException("Unsupported ISO8583 payloadAdapter: " + request.payloadAdapter());
    }
    String payload = request.payload();
    if (payload == null || payload.isBlank()) {
      throw new IllegalArgumentException("RAW_HEX payload must not be blank");
    }
    for (int i = 0; i < payload.length(); i++) {
      if (Character.isWhitespace(payload.charAt(i))) {
        throw new IllegalArgumentException("RAW_HEX payload must not contain whitespace");
      }
    }
    if ((payload.length() & 1) != 0) {
      throw new IllegalArgumentException("Invalid RAW_HEX payload length");
    }
    return HexFormat.of().parseHex(payload);
  }
}
