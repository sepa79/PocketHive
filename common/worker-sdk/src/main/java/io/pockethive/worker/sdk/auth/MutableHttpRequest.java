package io.pockethive.worker.sdk.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Responsibility: carry the mutable downstream HTTP credential-application target. Must not:
 * acquire credentials, coordinate tokens or choose authentication policy. Contract:
 * RESP-WORK-AUTH-APPLICATION —
 * docs/architecture/runtime-responsibilities.md#resp-work-auth-application.
 */
public final class MutableHttpRequest {
  private final String method;
  private String path;
  private final Map<String, String> headers;
  private final String body;

  public MutableHttpRequest(String method, String path, Map<String, String> headers, String body) {
    this.method = method;
    this.path = path;
    this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
    this.body = body;
  }

  public String method() {
    return method;
  }

  public String path() {
    return path;
  }

  public void setPath(String path) {
    this.path = Objects.requireNonNull(path, "path");
  }

  public Map<String, String> headers() {
    return headers;
  }

  public String body() {
    return body;
  }
}
