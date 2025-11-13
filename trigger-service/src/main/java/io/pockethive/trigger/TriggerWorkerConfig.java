package io.pockethive.trigger;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record TriggerWorkerConfig(
    long intervalMs,
    boolean singleRequest,
    String actionType,
    String command,
    String url,
    String method,
    String body,
    Map<String, String> headers
) {

  public TriggerWorkerConfig {
    if (intervalMs < 0L) {
      throw new IllegalArgumentException("intervalMs must be positive");
    }
    actionType = normalizeActionType(actionType);
    command = Objects.requireNonNull(command, "command");
    url = Objects.requireNonNull(url, "url");
    method = normalizeMethod(method);
    body = body == null ? "" : body;
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  private static String normalizeActionType(String value) {
    if (value == null) {
      throw new IllegalArgumentException("actionType must be provided");
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("actionType must not be blank");
    }
    return trimmed.toLowerCase(Locale.ROOT);
  }

  private static String normalizeMethod(String value) {
    if (value == null) {
      throw new IllegalArgumentException("method must be provided");
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException("method must not be blank");
    }
    return trimmed.toUpperCase(Locale.ROOT);
  }
}
