package io.pockethive.e2e.clients;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.pockethive.e2e.config.EnvironmentConfig.RabbitMqSettings;

/**
 * Simple REST client for the RabbitMQ management API limited to the endpoints required by the tests.
 */
public final class RabbitManagementClient {

  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

  private final WebClient webClient;

  private RabbitManagementClient(WebClient webClient) {
    this.webClient = webClient;
  }

  public static RabbitManagementClient create(RabbitMqSettings settings) {
    Objects.requireNonNull(settings, "settings");
    WebClient client = WebClient.builder()
        .baseUrl(settings.managementBaseUrl().toString())
        .defaultHeaders(headers -> headers.setBasicAuth(settings.username(), settings.password()))
        .build();
    return new RabbitManagementClient(client);
  }

  public List<QueueBinding> listBindings(String virtualHost, String queueName) {
    Objects.requireNonNull(virtualHost, "virtualHost");
    Objects.requireNonNull(queueName, "queueName");

    String safeVhost = virtualHost.isBlank() ? "/" : virtualHost;
    List<QueueBinding> bindings = webClient.get()
        .uri(uriBuilder -> uriBuilder
            .pathSegment("queues", safeVhost, queueName, "bindings")
            .build())
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .bodyToFlux(QueueBinding.class)
        .collectList()
        .timeout(HTTP_TIMEOUT)
        .block(HTTP_TIMEOUT);
    if (bindings == null) {
      throw new IllegalStateException("RabbitMQ management API returned no bindings for queue " + queueName);
    }
    return List.copyOf(bindings);
  }

  public record QueueBinding(String source,
                             String destination,
                             @JsonProperty("routing_key") String routingKey,
                             @JsonProperty("destination_type") String destinationType) {

    public String routingKey() {
      return routingKey == null ? "" : routingKey;
    }

    public String source() {
      return source == null ? "" : source;
    }

    public String destination() {
      return destination == null ? "" : destination;
    }

    public String destinationType() {
      return destinationType == null ? "" : destinationType;
    }

    public String toSummary() {
      return "QueueBinding{"
          + "source='" + source() + '\''
          + ", destination='" + destination() + '\''
          + ", routingKey='" + routingKey() + '\''
          + ", destinationType='" + destinationType() + '\''
          + '}';
    }

    public boolean hasDefaultKeyword() {
      return containsDefault(source())
          || containsDefault(destination())
          || containsDefault(routingKey())
          || containsDefault(destinationType());
    }

    private static boolean containsDefault(String value) {
      return value != null && value.toLowerCase(Locale.ROOT).contains("default");
    }
  }
}
