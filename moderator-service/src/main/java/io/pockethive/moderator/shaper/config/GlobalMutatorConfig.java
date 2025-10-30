package io.pockethive.moderator.shaper.config;

import java.util.Map;

public record GlobalMutatorConfig(String type, Map<String, Object> settings) {

  public GlobalMutatorConfig {
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("global mutator type must be provided");
    }
    settings = settings == null || settings.isEmpty() ? Map.of() : Map.copyOf(settings);
  }
}
