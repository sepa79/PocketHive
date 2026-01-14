package io.pockethive.requestbuilder;

import java.util.Objects;

public record RequestBuilderWorkerConfig(
    String templateRoot,
    String serviceId,
    boolean passThroughOnMissingTemplate
) {

  public RequestBuilderWorkerConfig {
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
