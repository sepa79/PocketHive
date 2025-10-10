package io.pockethive.e2e.support;

import java.io.IOException;
import java.util.Objects;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.pockethive.control.Confirmation;
import io.pockethive.control.ErrorConfirmation;
import io.pockethive.control.ReadyConfirmation;

/**
 * Utility responsible for decoding control-plane confirmation payloads based on their routing keys.
 */
public final class ControlPlaneEventParser {

  private final ObjectMapper objectMapper;

  public ControlPlaneEventParser() {
    this(createDefaultMapper());
  }

  public ControlPlaneEventParser(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public ParsedEvent parse(String routingKey, byte[] body) throws IOException {
    if (routingKey == null || body == null) {
      return ParsedEvent.ignored();
    }
    if (routingKey.startsWith("ev.ready.")) {
      Confirmation confirmation = objectMapper.readValue(body, ReadyConfirmation.class);
      return ParsedEvent.confirmation(confirmation);
    }
    if (routingKey.startsWith("ev.error.")) {
      Confirmation confirmation = objectMapper.readValue(body, ErrorConfirmation.class);
      return ParsedEvent.confirmation(confirmation);
    }
    if (routingKey.startsWith("ev.status-")) {
      StatusEvent status = objectMapper.readValue(body, StatusEvent.class);
      return ParsedEvent.status(status);
    }
    return ParsedEvent.ignored();
  }

  private static ObjectMapper createDefaultMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    return mapper;
  }

  public record ParsedEvent(Confirmation confirmation, StatusEvent status) {

    public static ParsedEvent confirmation(Confirmation confirmation) {
      return new ParsedEvent(confirmation, null);
    }

    public static ParsedEvent status(StatusEvent status) {
      return new ParsedEvent(null, status);
    }

    public static ParsedEvent ignored() {
      return new ParsedEvent(null, null);
    }

    public boolean hasConfirmation() {
      return confirmation != null;
    }

    public boolean hasStatus() {
      return status != null;
    }
  }
}
