package io.pockethive.moderator.shaper.config;

import java.util.Map;

public record SeedsConfig(String defaultSeed, Map<String, String> overrides) {

  public SeedsConfig {
    overrides = overrides == null || overrides.isEmpty() ? Map.of() : Map.copyOf(overrides);
  }

  public static SeedsConfig empty() {
    return new SeedsConfig(null, Map.of());
  }
}
