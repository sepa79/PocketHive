package io.pockethive.e2e.support;

import java.io.IOException;
import java.util.Objects;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandOutcome;

/**
 * Utility responsible for decoding control-plane event payloads based on their routing keys.
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
    if (routingKey.startsWith("event.outcome.")) {
      CommandOutcome outcome = objectMapper.readValue(body, CommandOutcome.class);
      return ParsedEvent.outcome(outcome);
    }
    if (routingKey.startsWith("event.alert.")) {
      AlertMessage alert = objectMapper.readValue(body, AlertMessage.class);
      return ParsedEvent.alert(alert);
    }
    if (routingKey.startsWith("event.metric.status-")) {
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

  public record ParsedEvent(CommandOutcome outcome, AlertMessage alert, StatusEvent status) {

    public static ParsedEvent outcome(CommandOutcome outcome) {
      return new ParsedEvent(outcome, null, null);
    }

    public static ParsedEvent alert(AlertMessage alert) {
      return new ParsedEvent(null, alert, null);
    }

    public static ParsedEvent status(StatusEvent status) {
      return new ParsedEvent(null, null, status);
    }

    public static ParsedEvent ignored() {
      return new ParsedEvent(null, null, null);
    }

    public boolean hasOutcome() {
      return outcome != null;
    }

    public boolean hasAlert() {
      return alert != null;
    }

    public boolean hasStatus() {
      return status != null;
    }
  }
}
