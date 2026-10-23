package io.pockethive.httpsequence;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

/**
 * Responsibility: execute resolved HTTP requests through explicit native and single-attempt transports.
 * Must not: select endpoints, render templates, interpret response documents or decide workflow retries.
 * Contract: RESP-HTTP-SEQUENCE-HTTP-EXECUTION — docs/architecture/runtime-responsibilities.md#resp-http-sequence-http-execution.
 */
final class ApacheHttpCallExecutor implements HttpCallExecutor {

  private static final int MAX_PORTABLE_RESPONSE_BYTES = 1024 * 1024;
  private final HttpClient httpClient;
  private final HttpClient portableHttpClient;

  ApacheHttpCallExecutor(HttpClient httpClient, HttpClient portableHttpClient) {
    this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    this.portableHttpClient = Objects.requireNonNull(portableHttpClient, "portableHttpClient");
  }

  @Override
  public HttpCallResult execute(URI target, RenderedCall call) throws Exception {
    return execute(httpClient, target, call, false);
  }

  @Override
  public HttpCallResult executeOnce(URI target, RenderedCall call) throws Exception {
    return execute(portableHttpClient, target, call, true);
  }

  private HttpCallResult execute(HttpClient transport, URI target, RenderedCall call, boolean portable) throws Exception {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(call, "call");

    HttpUriRequestBase request = new HttpUriRequestBase(call.method(), target);
    call.headers().forEach(request::addHeader);
    if (call.body() != null && (portable || !call.body().isBlank())) {
      ContentType contentType = contentType(call.headers());
      request.setEntity(new StringEntity(call.body(), contentType));
    }

    try {
      return transport.execute(request, response -> {
        int code = response.getCode();
        String responseBody = response.getEntity() == null
            ? ""
            : portable ? readPortableBody(response.getEntity())
                : EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        Map<String, java.util.List<String>> headers = toLowercaseHeaderMap(response);
        return new HttpCallResult(code, headers, responseBody, null);
      });
    } catch (Exception ex) {
      return new HttpCallResult(-1, Map.of(), "", ex.toString());
    }
  }

  private static String readPortableBody(HttpEntity entity) throws IOException {
    if (entity.getContentLength() > MAX_PORTABLE_RESPONSE_BYTES) {
      throw new IOException("Portable HTTP response exceeds one MiB");
    }
    try (var content = entity.getContent()) {
      byte[] bytes = content.readNBytes(MAX_PORTABLE_RESPONSE_BYTES + 1);
      if (bytes.length > MAX_PORTABLE_RESPONSE_BYTES) {
        throw new IOException("Portable HTTP response exceeds one MiB");
      }
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString();
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
