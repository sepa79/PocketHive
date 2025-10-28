package io.pockethive.moderator.shaper.config;

import java.util.Map;

public record StepMutatorConfig(String type, Map<String, Object> settings) {

  public StepMutatorConfig {
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("mutator type must be provided");
    }
    settings = settings == null || settings.isEmpty() ? Map.of() : Map.copyOf(settings);
  }
}
