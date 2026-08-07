package io.pockethive.requestexecution;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

/** Apache HttpClient adapter for the shared request-execution port. */
public final class ApacheHttpRequestExecutor implements RequestExecutor {
  private final HttpClient httpClient;

  public ApacheHttpRequestExecutor(HttpClient httpClient) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
  }

  @Override
  public HttpExecutionResult execute(HttpExecutionRequest request) throws Exception {
    Objects.requireNonNull(request, "request");
    HttpUriRequestBase apacheRequest = new HttpUriRequestBase(request.method(), request.target());
    request.headers().forEach(apacheRequest::addHeader);
    if (request.body() != null) {
      apacheRequest.setEntity(new StringEntity(request.body(), StandardCharsets.UTF_8));
    }
    return httpClient.execute(apacheRequest, response -> new HttpExecutionResult(
        response.getCode(), responseHeaders(response),
        response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8)));
  }

  private static Map<String, List<String>> responseHeaders(ClassicHttpResponse response) {
    Header[] responseHeaders = response.getHeaders();
    if (responseHeaders == null || responseHeaders.length == 0) {
      return Map.of();
    }
    Map<String, List<String>> headers = new LinkedHashMap<>();
    for (Header header : responseHeaders) {
      headers.computeIfAbsent(header.getName(), ignored -> new ArrayList<>()).add(header.getValue());
    }
    return headers;
  }
}
