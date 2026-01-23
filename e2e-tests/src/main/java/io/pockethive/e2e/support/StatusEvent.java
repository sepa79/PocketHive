package io.pockethive.e2e.support;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StatusEvent(
    Instant timestamp,
    String version,
    String kind,
    String type,
    String origin,
    Scope scope,
    String correlationId,
    String idempotencyKey,
    Data data
) {

  public StatusEvent {
    scope = scope == null ? new Scope(null, null, null) : scope;
    data = data == null ? new Data() : data;
  }

  @JsonIgnore
  public String swarmId() {
    return scope == null ? null : scope.swarmId();
  }

  @JsonIgnore
  public String role() {
    return scope == null ? null : scope.role();
  }

  @JsonIgnore
  public String instance() {
    return scope == null ? null : scope.instance();
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Scope(String swarmId, String role, String instance) {
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public static final class Data {

    public Boolean enabled;
    public Long tps;
    public Instant startedAt;
    public Map<String, Object> runtime;
    public Io io;
    public Map<String, Object> context;

    private final Map<String, Object> extra = new LinkedHashMap<>();

    public Data() {
    }

    public Boolean enabled() {
      return enabled;
    }

    public Long tps() {
      return tps;
    }

    public Instant startedAt() {
      return startedAt;
    }

    public Map<String, Object> runtime() {
      return normaliseMap(runtime);
    }

    public Io io() {
      return io == null ? new Io(null, null) : io;
    }

    public Map<String, Object> context() {
      return normaliseMap(context);
    }

    @JsonIgnore
    public Map<String, Object> extra() {
      return normaliseMap(extra);
    }

    @JsonAnySetter
    public void captureExtra(String key, Object value) {
      if ("enabled".equals(key)
          || "tps".equals(key)
          || "startedAt".equals(key)
          || "runtime".equals(key)
          || "io".equals(key)
          || "context".equals(key)) {
        return;
      }
      extra.put(key, value);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Io(IoSection work, IoSection control) {
    public Io {
      work = work == null ? new IoSection(null, null) : work;
      control = control == null ? new IoSection(null, null) : control;
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record IoSection(Queues queues, Map<String, Object> queueStats) {
    public IoSection {
      queues = queues == null ? new Queues(null, null, null) : queues;
      queueStats = normaliseMap(queueStats);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Queues(List<String> in, List<String> routes, List<String> out) {
    public Queues {
      in = in == null ? List.of() : List.copyOf(in);
      routes = routes == null ? List.of() : List.copyOf(routes);
      out = out == null ? List.of() : List.copyOf(out);
    }
  }

  private static Map<String, Object> normaliseMap(Map<String, Object> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }
}
