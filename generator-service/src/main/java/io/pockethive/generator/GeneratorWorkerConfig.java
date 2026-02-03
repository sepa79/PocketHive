package io.pockethive.generator;

import io.pockethive.worker.sdk.templating.MessageBodyType;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record GeneratorWorkerConfig(Message message) {

  public GeneratorWorkerConfig {
    Objects.requireNonNull(message, "message");
  }

  public record Message(MessageBodyType bodyType, String path, String method, String body, Map<String, String> headers) {

    public Message {
      bodyType = bodyType == null ? MessageBodyType.HTTP : bodyType;
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
  }
}
