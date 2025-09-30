package io.pockethive.e2e.support;

import java.io.IOException;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
    this.objectMapper = objectMapper;
  }

  public Confirmation parse(String routingKey, byte[] body) throws IOException {
    if (routingKey == null || body == null) {
      return null;
    }
    Class<? extends Confirmation> type;
    if (routingKey.startsWith("ev.ready.")) {
      type = ReadyConfirmation.class;
    } else if (routingKey.startsWith("ev.error.")) {
      type = ErrorConfirmation.class;
    } else {
      return null;
    }
    return objectMapper.readValue(body, type);
  }

  public StatusPayload parseStatus(String routingKey, byte[] body) throws IOException {
    if (routingKey == null || body == null) {
      return null;
    }
    if (!routingKey.startsWith("ev.status-full.") && !routingKey.startsWith("ev.status-delta.")) {
      return null;
    }
    return objectMapper.readValue(body, StatusPayload.class);
  }

  private static ObjectMapper createDefaultMapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    return mapper;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record StatusPayload(String swarmId, String role, Boolean enabled, Instant timestamp) {
  }
}
