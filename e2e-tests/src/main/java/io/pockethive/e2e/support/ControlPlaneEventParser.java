package io.pockethive.e2e.support;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Objects;

import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandOutcome;
import io.pockethive.control.ControlPlaneEnvelope;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.codec.ControlPlaneCodec;

/**
 * E2E projection over envelopes decoded by the canonical production codec.
 */
public final class ControlPlaneEventParser {

  private final ControlPlaneCodec codec;

  public ControlPlaneEventParser() {
    this(ControlPlaneCodec.create());
  }

  public ControlPlaneEventParser(ControlPlaneCodec codec) {
    this.codec = Objects.requireNonNull(codec, "codec");
  }

  public ParsedEvent parse(String routingKey, byte[] body) {
    if (routingKey == null || body == null) {
      return ParsedEvent.ignored();
    }
    ControlPlaneEnvelope envelope = codec.decode(new String(body, UTF_8), routingKey);
    if (envelope instanceof CommandOutcome outcome) {
      return ParsedEvent.outcome(outcome);
    }
    if (envelope instanceof AlertMessage alert) {
      return ParsedEvent.alert(alert);
    }
    if (envelope instanceof StatusMetric status) {
      return ParsedEvent.status(new StatusEvent(status));
    }
    return ParsedEvent.ignored();
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
