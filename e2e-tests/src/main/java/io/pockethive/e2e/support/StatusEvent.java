package io.pockethive.e2e.support;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StatusEvent(
    String event,
    String version,
    String messageId,
    Instant timestamp,
    String location,
    String kind,
    String role,
    String instance,
    String origin,
    String swarmId,
    Boolean enabled,
    String state,
    Instant watermark,
    Long maxStalenessSec,
    Totals totals,
    Map<String, Object> queueStats,
    List<String> publishes,
    Queues queues,
    Map<String, Object> data,
    String traffic) {

  public StatusEvent {
    queueStats = normaliseMap(queueStats);
    publishes = publishes == null ? List.of() : List.copyOf(publishes);
    queues = queues == null ? new Queues(null, null) : queues;
    data = normaliseMap(data);
  }

  private static Map<String, Object> normaliseMap(Map<String, Object> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Queues(QueueEndpoints work, QueueEndpoints control) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record QueueEndpoints(List<String> in, List<String> routes, List<String> out) {

    public QueueEndpoints {
      in = in == null ? List.of() : List.copyOf(in);
      routes = routes == null ? List.of() : List.copyOf(routes);
      out = out == null ? List.of() : List.copyOf(out);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Totals(int desired, int healthy, int running, int enabled) {
  }
}
