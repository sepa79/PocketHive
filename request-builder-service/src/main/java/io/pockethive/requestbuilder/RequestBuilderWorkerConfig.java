package io.pockethive.requestbuilder;

import io.pockethive.swarm.model.BeeConfigKeys;
import java.util.Map;
import java.util.Objects;

public record RequestBuilderWorkerConfig(
    String templateRoot,
    String serviceId,
    Boolean passThroughOnMissingTemplate,
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
    templateRoot = requireNonBlank(templateRoot, "templateRoot");
    serviceId = requireNonBlank(serviceId, "serviceId");
    passThroughOnMissingTemplate = Objects.requireNonNull(passThroughOnMissingTemplate, "passThroughOnMissingTemplate");
    vars = vars == null ? Map.of() : Map.copyOf(vars);
    privateConfig = privateConfig == null ? Map.of() : Map.copyOf(privateConfig);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> authProfileSutContext() {
    Object authProfile = privateConfig.get(BeeConfigKeys.AUTH_PROFILE);
    if (!(authProfile instanceof Map<?, ?> rawAuthProfile)) {
      return Map.of();
    }
    Object sut = rawAuthProfile.get(BeeConfigKeys.SUT);
    if (sut instanceof Map<?, ?> rawSut) {
      return (Map<String, Object>) rawSut;
    }
    return Map.of();
  }

  private static String requireNonBlank(String value, String field) {
    Objects.requireNonNull(value, field);
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return trimmed;
  }
}
