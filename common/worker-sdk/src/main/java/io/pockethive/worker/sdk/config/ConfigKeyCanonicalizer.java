package io.pockethive.worker.sdk.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Canonicalises configuration maps so worker config binding can rely on consistent camelCase keys.
 * <p>
 * Behaviour matches {@link CanonicalWorkerProperties}: keys containing {@code -}, {@code _}, or {@code .}
 * are converted to camelCase, while dictionary-like blocks such as {@code headers} preserve key names.
 */
public final class ConfigKeyCanonicalizer {

  private static final Set<String> DICTIONARY_KEYS = Set.of("headers");

  private ConfigKeyCanonicalizer() {
  }

  public static Map<String, Object> canonicalise(Map<?, ?> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> canonical = canonicaliseMap(source, null);
    return canonical.isEmpty() ? Map.of() : Map.copyOf(canonical);
  }

  private static Map<String, Object> canonicaliseMap(Map<?, ?> source, String parentKey) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    boolean dictionary = parentKey != null && isDictionaryKey(parentKey);
    source.forEach((key, value) -> {
      if (key == null) {
        return;
      }
      String canonical = dictionary ? key.toString() : toCamelCase(key.toString());
      String childParent = dictionary ? parentKey : canonical;
      result.put(canonical, canonicaliseValue(value, childParent));
    });
    return result;
  }

  private static Object canonicaliseValue(Object value, String parentKey) {
    if (value instanceof Map<?, ?> map) {
      return canonicaliseMap(map, parentKey);
    }
    if (value instanceof List<?> list) {
      return list.stream().map(item -> canonicaliseValue(item, parentKey)).toList();
    }
    return value;
  }

  private static String toCamelCase(String key) {
    if (key == null || key.isBlank()) {
      return key;
    }
    boolean hasSeparator = false;
    for (int i = 0; i < key.length(); i++) {
      char ch = key.charAt(i);
      if (ch == '-' || ch == '_' || ch == '.') {
        hasSeparator = true;
        break;
      }
    }
    if (!hasSeparator) {
      if (key.length() == 1) {
        return key.toLowerCase();
      }
      return Character.toLowerCase(key.charAt(0)) + key.substring(1);
    }
    StringBuilder builder = new StringBuilder();
    boolean upperNext = false;
    for (int i = 0; i < key.length(); i++) {
      char ch = key.charAt(i);
      if (ch == '-' || ch == '_' || ch == '.') {
        upperNext = true;
        continue;
      }
      if (upperNext) {
        builder.append(Character.toUpperCase(ch));
        upperNext = false;
      } else {
        builder.append(Character.toLowerCase(ch));
      }
    }
    return builder.toString();
  }

  private static boolean isDictionaryKey(String key) {
    if (key == null) {
      return false;
    }
    if (DICTIONARY_KEYS.contains(key)) {
      return true;
    }
    return key.endsWith("Headers");
  }
}
