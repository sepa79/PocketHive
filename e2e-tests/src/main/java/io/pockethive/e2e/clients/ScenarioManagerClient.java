package io.pockethive.e2e.clients;

import java.net.URI;
import java.util.Objects;

import org.springframework.web.reactive.function.client.WebClient;

/**
 * Simple client used to browse the Scenario Manager catalogue in acceptance tests.
 */
public final class ScenarioManagerClient {

  private final WebClient webClient;

  private ScenarioManagerClient(WebClient webClient) {
    this.webClient = webClient;
  }

  public static ScenarioManagerClient create(URI baseUrl) {
    Objects.requireNonNull(baseUrl, "baseUrl");
    return new ScenarioManagerClient(WebClient.builder().baseUrl(baseUrl.toString()).build());
  }

  public WebClient webClient() {
    return webClient;
  }
}
