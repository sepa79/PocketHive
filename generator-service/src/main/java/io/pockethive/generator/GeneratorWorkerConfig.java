package io.pockethive.generator;

import io.pockethive.worker.sdk.templating.MessageBodyType;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record GeneratorWorkerConfig(Message message, Map<String, Object> vars) {

  public GeneratorWorkerConfig {
    Objects.requireNonNull(message, "message");
    vars = vars == null ? Map.of() : Map.copyOf(vars);
  }

  public record Message(MessageBodyType bodyType, String path, String method, String body, Map<String, String> headers) {

    public Message {
      bodyType = Objects.requireNonNull(bodyType, "message.bodyType");
      if (bodyType == MessageBodyType.HTTP) {
        path = requireNonBlank(path, "message.path");
        method = normalizeMethod(requireNonBlank(method, "message.method"));
      }
      body = Objects.requireNonNull(body, "message.body");
      headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    private static String requireNonBlank(String value, String field) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(field + " must be provided");
      }
      return value.trim();
    }

    private static String normalizeMethod(String value) {
      return value.trim().toUpperCase(Locale.ROOT);
    }
  }
}
