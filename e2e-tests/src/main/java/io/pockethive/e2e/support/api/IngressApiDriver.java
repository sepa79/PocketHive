package io.pockethive.e2e.support.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Objects;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.pockethive.e2e.config.EnvironmentConfig.ServiceEndpoints;

/**
 * Thin ingress-only API executor shared by Cucumber steps.
 */
public final class IngressApiDriver {

  private final ServiceEndpoints endpoints;
  private final ObjectMapper objectMapper;

  public IngressApiDriver(ServiceEndpoints endpoints, ObjectMapper objectMapper) {
    this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  public ApiResponse execute(ApiService service,
                             String method,
                             String path,
                             String body,
                             MediaType contentType,
                             String bearerToken) {
    HttpMethod httpMethod = HttpMethod.valueOf(
        Objects.requireNonNull(method, "method").trim().toUpperCase(Locale.ROOT));
    WebClient client = client(service, bearerToken);
    try {
      WebClient.RequestBodySpec request = client.method(httpMethod)
          .uri(Objects.requireNonNull(path, "path"))
          .accept(MediaType.ALL);
      var exchange = (body != null && !body.isBlank())
          ? request.contentType(contentType).bodyValue(body)
          : request;
      return exchange.retrieve()
          .toEntity(String.class)
          .blockOptional()
          .map(entity -> new ApiResponse(entity.getStatusCode().value(), entity.getBody(), parseJson(entity.getBody())))
          .orElseGet(() -> new ApiResponse(0, null, null));
    } catch (WebClientResponseException ex) {
      return new ApiResponse(ex.getStatusCode().value(), ex.getResponseBodyAsString(), parseJson(ex.getResponseBodyAsString()));
    }
  }

  public int getStatus(ApiService service, String path, String bearerToken) {
    return execute(service, "GET", path, null, MediaType.APPLICATION_JSON, bearerToken).status();
  }

  private WebClient client(ApiService service, String bearerToken) {
    WebClient.Builder builder = WebClient.builder().baseUrl(service.baseUrl(endpoints).toString());
    if (bearerToken != null && !bearerToken.isBlank()) {
      builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
    }
    return builder.build();
  }

  private JsonNode parseJson(String responseBody) {
    if (responseBody == null || responseBody.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readTree(responseBody);
    } catch (Exception ex) {
      return null;
    }
  }
}
