package io.pockethive.requestbuilder;

import java.util.Map;
import java.util.Objects;

public record RequestBuilderWorkerConfig(
    String templateRoot,
    String serviceId,
    boolean passThroughOnMissingTemplate,
    Map<String, Object> vars,
    Map<String, Object> privateConfig
) {

  public RequestBuilderWorkerConfig(String templateRoot,
                                    String serviceId,
                                    boolean passThroughOnMissingTemplate,
                                    Map<String, Object> vars) {
    this(templateRoot, serviceId, passThroughOnMissingTemplate, vars, Map.of());
  }

  public RequestBuilderWorkerConfig {
    templateRoot = normalize(templateRoot, "/app/templates/http");
    serviceId = normalize(serviceId, "default");
    vars = vars == null ? Map.of() : Map.copyOf(vars);
    privateConfig = privateConfig == null ? Map.of() : Map.copyOf(privateConfig);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> authProfileSutContext() {
    Object authProfile = privateConfig.get("authProfile");
    if (!(authProfile instanceof Map<?, ?> rawAuthProfile)) {
      return Map.of();
    }
    Object sut = rawAuthProfile.get("sut");
    if (sut instanceof Map<?, ?> rawSut) {
      return (Map<String, Object>) rawSut;
    }
    return Map.of();
  }

  private static String normalize(String value, String defaultValue) {
    if (value == null) {
      return Objects.requireNonNull(defaultValue, "defaultValue");
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? defaultValue : trimmed;
  }
}
