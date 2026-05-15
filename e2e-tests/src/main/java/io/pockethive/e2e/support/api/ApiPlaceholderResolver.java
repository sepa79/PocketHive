package io.pockethive.e2e.support.api;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Resolves scenario-local placeholders used by the ingress auth pack.
 */
public final class ApiPlaceholderResolver {

  private final Map<String, String> resolvedSwarmIds = new HashMap<>();
  private final Map<String, String> rememberedValues = new HashMap<>();

  public String resolveSwarmId(String alias) {
    String normalizedAlias = Objects.requireNonNull(alias, "alias").trim();
    return resolvedSwarmIds.computeIfAbsent(normalizedAlias,
        key -> "%s-%s".formatted(key, UUID.randomUUID().toString().substring(0, 8)));
  }

  public void rememberValue(String key, String value) {
    rememberedValues.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
  }

  public void rememberUniqueValue(String prefix, String key) {
    rememberValue(key, "%s-%s".formatted(prefix, UUID.randomUUID().toString().substring(0, 8)));
  }

  public String resolveTemplate(String raw) {
    String resolved = Objects.requireNonNull(raw, "raw");
    for (Map.Entry<String, String> entry : resolvedSwarmIds.entrySet()) {
      resolved = resolved.replace("{{swarm:%s}}".formatted(entry.getKey()), entry.getValue());
    }
    for (Map.Entry<String, String> entry : rememberedValues.entrySet()) {
      resolved = resolved.replace("{{value:%s}}".formatted(entry.getKey()), entry.getValue());
    }
    if (resolved.contains("{{swarm:") || resolved.contains("{{value:")) {
      throw new IllegalStateException("Unresolved placeholder in API request: " + resolved);
    }
    return resolved;
  }

  public void clear() {
    resolvedSwarmIds.clear();
    rememberedValues.clear();
  }
}
