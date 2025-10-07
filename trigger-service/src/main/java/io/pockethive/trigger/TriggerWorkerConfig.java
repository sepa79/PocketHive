package io.pockethive.trigger;

import java.util.Locale;
import java.util.Map;

public record TriggerWorkerConfig(
    boolean enabled,
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
    intervalMs = Math.max(0L, intervalMs);
    actionType = normalizeActionType(actionType);
    command = command == null ? "" : command;
    url = url == null ? "" : url;
    method = normalizeMethod(method);
    body = body == null ? "" : body;
    headers = headers == null ? Map.of() : Map.copyOf(headers);
  }

  private static String normalizeActionType(String value) {
    if (value == null || value.isBlank()) {
      return "none";
    }
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private static String normalizeMethod(String value) {
    if (value == null || value.isBlank()) {
      return "GET";
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }
}
