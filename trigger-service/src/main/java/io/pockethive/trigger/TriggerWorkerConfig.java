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
  static final String ACTION_REST = "rest";
  static final String ACTION_SHELL = "shell";

  public TriggerWorkerConfig {
    if (intervalMs < 0L) {
      throw new IllegalArgumentException("intervalMs must be non-negative");
    }
    actionType = normalizeActionType(actionType);
    switch (actionType) {
      case ACTION_SHELL -> {
        command = requireNonBlank(command, "command");
        url = optionalTrim(url);
        method = optionalMethod(method);
        body = optionalTrim(body);
        headers = headers == null ? null : Map.copyOf(headers);
      }
      case ACTION_REST -> {
        command = optionalTrim(command);
        url = requireNonBlank(url, "url");
        method = normalizeMethod(method);
        body = Objects.requireNonNull(body, "body");
        headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
      }
      default -> throw new IllegalArgumentException("unsupported trigger action type: " + actionType);
    }
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
    return requireNonBlank(value, "method").toUpperCase(Locale.ROOT);
  }

  private static String optionalMethod(String value) {
    String trimmed = optionalTrim(value);
    return trimmed == null ? null : trimmed.toUpperCase(Locale.ROOT);
  }

  private static String optionalTrim(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static String requireNonBlank(String value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " must be provided");
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return trimmed;
  }
}
