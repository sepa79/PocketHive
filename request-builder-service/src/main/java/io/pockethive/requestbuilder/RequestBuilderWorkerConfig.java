package io.pockethive.requestbuilder;

import java.util.Map;
import java.util.Objects;

public record RequestBuilderWorkerConfig(
    String templateRoot,
    String serviceId,
    boolean passThroughOnMissingTemplate,
    Map<String, Object> vars
) {

  public RequestBuilderWorkerConfig {
    templateRoot = normalize(templateRoot, "/app/templates/http");
    serviceId = normalize(serviceId, "default");
    vars = vars == null ? Map.of() : Map.copyOf(vars);
  }

  private static String normalize(String value, String defaultValue) {
    if (value == null) {
      return Objects.requireNonNull(defaultValue, "defaultValue");
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? defaultValue : trimmed;
  }
}
