package io.pockethive.e2e.clients;

import java.net.URI;
import java.util.Objects;

import org.springframework.web.reactive.function.client.WebClient;

/**
 * Thin wrapper around {@link WebClient} to access the Orchestrator REST API.
 */
public final class OrchestratorClient {

  private final WebClient webClient;

  private OrchestratorClient(WebClient webClient) {
    this.webClient = webClient;
  }

  public static OrchestratorClient create(URI baseUrl) {
    Objects.requireNonNull(baseUrl, "baseUrl");
    return new OrchestratorClient(WebClient.builder().baseUrl(baseUrl.toString()).build());
  }

  public WebClient webClient() {
    return webClient;
  }
}
