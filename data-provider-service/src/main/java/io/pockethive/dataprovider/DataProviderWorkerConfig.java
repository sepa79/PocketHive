package io.pockethive.dataprovider;

import io.pockethive.worker.sdk.templating.MessageBodyType;
import java.util.Map;
import java.util.Objects;

public record DataProviderWorkerConfig(Template template) {

  public DataProviderWorkerConfig {
    template = template == null ? Template.defaultTemplate() : template;
  }

  public record Template(
      MessageBodyType bodyType,
      String path,
      String method,
      String body,
      Map<String, String> headers
  ) {

    public Template {
      bodyType = bodyType == null ? MessageBodyType.HTTP : bodyType;
      path = normalizePath(path);
      method = normalizeMethod(method);
      body = body == null ? "" : body;
      headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static Template defaultTemplate() {
      return new Template(
          MessageBodyType.HTTP,
          "/",
          "POST",
          "{{ payload }}",
          Map.of("content-type", "application/json"));
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
      return value.trim().toUpperCase();
    }
  }
}
