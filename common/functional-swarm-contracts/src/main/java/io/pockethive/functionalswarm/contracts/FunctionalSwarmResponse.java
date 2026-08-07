package io.pockethive.functionalswarm.contracts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Stable caller-facing result for either local or remote Functional Swarm execution. */
public record FunctionalSwarmResponse(int statusCode, Map<String, List<String>> headers, String body) {
  public FunctionalSwarmResponse {
    if (statusCode < 100 || statusCode > 599) {
      throw new IllegalArgumentException("statusCode must be a valid HTTP status");
    }
    headers = copyHeaders(headers);
    body = Objects.requireNonNull(body, "body");
  }

  private static Map<String, List<String>> copyHeaders(Map<String, List<String>> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    Map<String, List<String>> copy = new LinkedHashMap<>();
    source.forEach((name, values) -> copy.put(
        Objects.requireNonNull(name, "header name"),
        List.copyOf(new ArrayList<>(Objects.requireNonNull(values, "header values")))));
    return Map.copyOf(copy);
  }
}
