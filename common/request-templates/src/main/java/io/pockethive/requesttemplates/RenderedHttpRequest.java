package io.pockethive.requesttemplates;

import io.pockethive.swarm.model.ResultRules;
import io.pockethive.worker.sdk.api.HttpRequestEnvelope;
import java.util.Map;

/** Canonical HTTP template rendering result before target resolution and execution. */
public record RenderedHttpRequest(
    String method,
    String path,
    Map<String, String> headers,
    String body,
    ResultRules resultRules
) {

  public RenderedHttpRequest {
    HttpRequestEnvelope.HttpRequest validated = new HttpRequestEnvelope.HttpRequest(method, path, headers, body);
    method = validated.method();
    path = validated.path();
    headers = validated.headers();
    body = body == null ? "" : body;
  }

  public HttpRequestEnvelope toEnvelope() {
    return HttpRequestEnvelope.of(new HttpRequestEnvelope.HttpRequest(method, path, headers, body), resultRules);
  }
}
