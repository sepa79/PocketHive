package io.pockethive.httpbuilder;

import java.util.Objects;

public record HttpBuilderWorkerConfig(
    String templateRoot,
    String serviceId
) {

  public HttpBuilderWorkerConfig {
    templateRoot = normalize(templateRoot, "/app/http-templates");
    serviceId = normalize(serviceId, "default");
  }

  private static String normalize(String value, String defaultValue) {
    if (value == null) {
      return Objects.requireNonNull(defaultValue, "defaultValue");
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? defaultValue : trimmed;
  }
}

