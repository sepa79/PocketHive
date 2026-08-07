package io.pockethive.functionalswarm.contracts;

import java.util.LinkedHashMap;
import java.util.Map;

/** Public input shared by local and remote Functional Swarm execution. */
public record FunctionalSwarmInvocation(String payload, Map<String, String> headers) {
  public FunctionalSwarmInvocation {
    if (payload == null || payload.isBlank()) {
      throw new IllegalArgumentException("payload must not be blank");
    }
    headers = validateHeaders(headers);
  }

  private static Map<String, String> validateHeaders(Map<String, String> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    Map<String, String> copy = new LinkedHashMap<>();
    source.forEach((name, value) -> {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("invocation header name must not be blank");
      }
      if (value == null) {
        throw new IllegalArgumentException("invocation header value must not be null");
      }
      if (FunctionalSwarmProtocol.REPLY_LIST_HEADER.equalsIgnoreCase(name)
          || FunctionalSwarmProtocol.REQUEST_ID_HEADER.equalsIgnoreCase(name)) {
        throw new IllegalArgumentException("invocation must not set Functional Swarm transport header: " + name);
      }
      copy.put(name, value);
    });
    return Map.copyOf(copy);
  }

  public static FunctionalSwarmInvocation of(String payload) {
    return new FunctionalSwarmInvocation(payload, Map.of());
  }
}
