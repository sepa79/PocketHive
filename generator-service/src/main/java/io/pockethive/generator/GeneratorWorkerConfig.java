package io.pockethive.generator;

import io.pockethive.worker.sdk.input.SchedulerStates;
import java.util.Locale;
import java.util.Map;

public record GeneratorWorkerConfig(
    double ratePerSec,
    boolean singleRequest,
    Message message
) implements SchedulerStates.RateConfig {

  public GeneratorWorkerConfig {
    ratePerSec = Double.isNaN(ratePerSec) || ratePerSec < 0 ? 0.0 : ratePerSec;
    message = message == null ? Message.defaults() : message;
  }

  public record Message(String path, String method, String body, Map<String, String> headers) {

    private static final Message DEFAULT = new Message("/", "GET", "", Map.of());

    public Message {
      path = normalizePath(path);
      method = normalizeMethod(method);
      body = body == null ? "" : body;
      headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    private static String normalizePath(String value) {
      if (value == null || value.isBlank()) {
        return "/";
      }
      return value.trim();
    }

    private static String normalizeMethod(String value) {
      if (value == null || value.isBlank()) {
        return "GET";
      }
      return value.trim().toUpperCase(Locale.ROOT);
    }

    static Message defaults() {
      return DEFAULT;
    }
  }
}
