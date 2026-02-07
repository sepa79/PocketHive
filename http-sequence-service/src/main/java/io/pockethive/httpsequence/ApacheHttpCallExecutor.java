package io.pockethive.httpsequence;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

final class ApacheHttpCallExecutor implements HttpCallExecutor {

  private final HttpClient httpClient;

  ApacheHttpCallExecutor(HttpClient httpClient) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
  }

  @Override
  public HttpCallResult execute(String baseUrl, RenderedCall call) throws Exception {
    Objects.requireNonNull(call, "call");
    if (baseUrl == null || baseUrl.isBlank()) {
      throw new IllegalArgumentException("baseUrl is required");
    }

    String path = call.path() == null ? "" : call.path();
    java.net.URI target;
    try {
      target = java.net.URI.create(baseUrl + path);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Invalid baseUrl/path: " + ex.getMessage(), ex);
    }

    HttpUriRequestBase request = new HttpUriRequestBase(call.method(), target);
    call.headers().forEach(request::addHeader);
    if (call.body() != null && !call.body().isBlank()) {
      ContentType contentType = contentType(call.headers());
      request.setEntity(new StringEntity(call.body(), contentType));
    }

    try {
      ClassicHttpResponse response = (ClassicHttpResponse) httpClient.execute(request);
      int code = response.getCode();
      String responseBody = response.getEntity() == null
          ? ""
          : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
      Map<String, java.util.List<String>> headers = toLowercaseHeaderMap(response);
      return new HttpCallResult(code, headers, responseBody, null);
    } catch (Exception ex) {
      return new HttpCallResult(-1, Map.of(), "", ex.toString());
    }
  }

  private static ContentType contentType(Map<String, String> headers) {
    if (headers == null) {
      return ContentType.TEXT_PLAIN;
    }
    String value = headers.getOrDefault("content-type", headers.get("Content-Type"));
    if (value == null || value.isBlank()) {
      return ContentType.TEXT_PLAIN;
    }
    try {
      return ContentType.parse(value);
    } catch (Exception ex) {
      return ContentType.TEXT_PLAIN;
    }
  }

  private static Map<String, java.util.List<String>> toLowercaseHeaderMap(ClassicHttpResponse response) {
    Header[] headers = response.getHeaders();
    if (headers == null || headers.length == 0) {
      return Map.of();
    }
    Map<String, java.util.List<String>> result = new java.util.LinkedHashMap<>();
    for (Header header : headers) {
      String key = header.getName() == null ? "" : header.getName().toLowerCase(java.util.Locale.ROOT);
      result.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(header.getValue());
    }
    return Map.copyOf(result);
  }
}

