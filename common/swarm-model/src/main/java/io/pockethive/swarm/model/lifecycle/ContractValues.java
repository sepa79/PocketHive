package io.pockethive.swarm.model.lifecycle;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;
import java.util.LinkedHashMap;

final class ContractValues {

  private ContractValues() {
  }

  static String requireText(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }

  static String optionalText(String value) {
    return value == null ? null : requireText("value", value);
  }

  static <T> List<T> immutableList(String field, List<T> values) {
    Objects.requireNonNull(values, field);
    return List.copyOf(values);
  }

  static Map<String, Object> immutableContext(Map<String, Object> values) {
    Objects.requireNonNull(values, "context");
    LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
    values.forEach((key, value) -> copy.put(requireText("context key", key), value));
    return Collections.unmodifiableMap(copy);
  }
}
