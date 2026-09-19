package io.pockethive.e2e.support;

import io.pockethive.control.ControlScope;
import io.pockethive.control.StatusMetric;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** E2E query projection backed by the canonical {@link StatusMetric} envelope. */
public record StatusEvent(StatusMetric envelope) {

  public StatusEvent {
    Objects.requireNonNull(envelope, "envelope");
  }

  public Instant timestamp() {
    return envelope.timestamp();
  }

  public String version() {
    return envelope.version();
  }

  public String kind() {
    return envelope.kind();
  }

  public String type() {
    return envelope.type();
  }

  public String origin() {
    return envelope.origin();
  }

  public ControlScope scope() {
    return envelope.scope();
  }

  public String correlationId() {
    return envelope.correlationId();
  }

  public String idempotencyKey() {
    return envelope.idempotencyKey();
  }

  public Map<String, Object> runtime() {
    return envelope.runtime();
  }

  public String swarmId() {
    return envelope.scope().swarmId();
  }

  public String role() {
    return envelope.scope().role();
  }

  public String instance() {
    return envelope.scope().instance();
  }

  public Data data() {
    return Data.from(envelope.data());
  }

  public record Data(
      Boolean enabled,
      Long tps,
      Instant startedAt,
      Io io,
      Map<String, Object> context,
      Map<String, Object> extra) {

    private static Data from(Map<String, Object> source) {
      Map<String, Object> data = normaliseMap(source);
      Map<String, Object> extra = new LinkedHashMap<>(data);
      Object enabled = extra.remove("enabled");
      Object tps = extra.remove("tps");
      Object startedAt = extra.remove("startedAt");
      Object io = extra.remove("io");
      Object context = extra.remove("context");
      return new Data(
          enabled instanceof Boolean value ? value : null,
          tps instanceof Number value ? value.longValue() : null,
          instant(startedAt),
          Io.from(map(io)),
          normaliseMap(map(context)),
          normaliseMap(extra));
    }
  }

  public record Io(IoSection work, IoSection control) {

    private static Io from(Map<String, Object> source) {
      return new Io(
          IoSection.from(map(source.get("work"))),
          IoSection.from(map(source.get("control"))));
    }
  }

  public record IoSection(Queues queues, Map<String, Object> queueStats) {

    private static IoSection from(Map<String, Object> source) {
      return new IoSection(
          Queues.from(map(source.get("queues"))),
          normaliseMap(map(source.get("queueStats"))));
    }
  }

  public record Queues(List<String> in, List<String> routes, List<String> out) {

    private static Queues from(Map<String, Object> source) {
      return new Queues(strings(source.get("in")), strings(source.get("routes")), strings(source.get("out")));
    }
  }

  private static Instant instant(Object value) {
    if (value instanceof Instant instant) {
      return instant;
    }
    if (value instanceof String text && !text.isBlank()) {
      return Instant.parse(text);
    }
    return null;
  }

  private static List<String> strings(Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
  }

  private static Map<String, Object> map(Object value) {
    if (!(value instanceof Map<?, ?> source)) {
      return Map.of();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    source.forEach((key, entry) -> result.put(Objects.toString(key), entry));
    return result;
  }

  private static Map<String, Object> normaliseMap(Map<String, Object> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }
}
