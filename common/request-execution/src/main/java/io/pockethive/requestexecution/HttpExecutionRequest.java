package io.pockethive.requestexecution;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Concrete HTTP request after template rendering and target resolution. */
public record HttpExecutionRequest(String method, URI target, Map<String, String> headers, String body) {
  public HttpExecutionRequest {
    method = requireMethod(method);
    target = Objects.requireNonNull(target, "target");
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  private static String requireMethod(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("method must not be blank");
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }
}
