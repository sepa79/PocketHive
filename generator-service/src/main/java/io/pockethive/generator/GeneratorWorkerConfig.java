package io.pockethive.generator;

import java.util.Locale;
import java.util.Map;

public record GeneratorWorkerConfig(
    boolean enabled,
    double ratePerSec,
    boolean singleRequest,
    String path,
    String method,
    String body,
    Map<String, String> headers
) {

  public GeneratorWorkerConfig {
    ratePerSec = Double.isNaN(ratePerSec) || ratePerSec < 0 ? 0.0 : ratePerSec;
    path = normalizePath(path);
    method = normalizeMethod(method);
    body = body == null ? "" : body;
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  private static String normalizePath(String value) {
    if (value == null || value.isBlank()) {
      return "/";
    }
    return value.trim();
  }

  private static String normalizeMethod(String value) {
    if (value == null || value.isBlank()) {
      return "GET";
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }
}
