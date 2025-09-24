package io.pockethive.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.pockethive.e2e.clients.OrchestratorClient;
import io.pockethive.e2e.clients.RabbitSubscriptions;
import io.pockethive.e2e.clients.ScenarioManagerClient;
import io.pockethive.e2e.config.EnvironmentConfig;
import io.pockethive.e2e.config.EnvironmentConfig.ServiceEndpoints;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Step definitions covering the Phase 1 smoke checks for a deployed PocketHive stack.
 */
public class SmokeSteps {

  private static final Logger LOGGER = LoggerFactory.getLogger(SmokeSteps.class);
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private ServiceEndpoints endpoints;
  private OrchestratorClient orchestratorClient;
  private ScenarioManagerClient scenarioManagerClient;
  private RabbitSubscriptions rabbitSubscriptions;
  private WebClient uiWebClient;
  private URI orchestratorBaseUrl;
  private URI scenarioManagerBaseUrl;
  private URI uiBaseUrl;

  private HttpProbeResult orchestratorHealth;
  private HttpProbeResult scenarioManagerHealth;
  private HttpProbeResult uiHealth;
  private RabbitProbeResult rabbitProbe;
  private HttpProbeResult defaultSwarmQuery;

  @Given("the deployed PocketHive endpoints are configured")
  public void theDeployedPocketHiveEndpointsAreConfigured() {
    try {
      endpoints = EnvironmentConfig.loadServiceEndpoints();
    } catch (IllegalStateException ex) {
      Assumptions.assumeTrue(false, () -> "Skipping smoke checks: " + ex.getMessage());
    }

    Optional<URI> maybeUiBase = endpoints.uiBaseUrl();
    if (maybeUiBase.isEmpty()) {
      Assumptions.assumeTrue(false, () -> "Skipping smoke checks: configure UI_BASE_URL or UI_WEBSOCKET_URI");
    }

    orchestratorBaseUrl = endpoints.orchestratorBaseUrl();
    scenarioManagerBaseUrl = endpoints.scenarioManagerBaseUrl();
    uiBaseUrl = maybeUiBase.orElseThrow();

    orchestratorClient = OrchestratorClient.create(orchestratorBaseUrl);
    scenarioManagerClient = ScenarioManagerClient.create(scenarioManagerBaseUrl);
    rabbitSubscriptions = RabbitSubscriptions.fromUri(endpoints.rabbitMqUri());
    uiWebClient = WebClient.builder().baseUrl(uiBaseUrl.toString()).build();

    LOGGER.info("Smoke checks configured with endpoints: {}", endpoints.asMap());
  }

  @When("I call the platform health endpoints")
  public void iCallThePlatformHealthEndpoints() {
    orchestratorHealth = probe(orchestratorClient.webClient(), orchestratorBaseUrl, "/actuator/health");
    scenarioManagerHealth = probe(scenarioManagerClient.webClient(), scenarioManagerBaseUrl, "/actuator/health");
    uiHealth = probe(uiWebClient, uiBaseUrl, "/healthz");
    rabbitProbe = probeRabbit();

    LOGGER.info("Orchestrator health {} -> {}", orchestratorHealth.uri(), orchestratorHealth.summary());
    LOGGER.info("Scenario Manager health {} -> {}", scenarioManagerHealth.uri(), scenarioManagerHealth.summary());
    LOGGER.info("UI proxy health {} -> {}", uiHealth.uri(), uiHealth.summary());
    LOGGER.info("RabbitMQ connectivity -> {}", rabbitProbe.summary());
  }

  @Then("the orchestrator and scenario manager report UP")
  public void theOrchestratorAndScenarioManagerReportUp() {
    assertActuatorUp("Orchestrator", orchestratorHealth);
    assertActuatorUp("Scenario Manager", scenarioManagerHealth);
  }

  @Then("RabbitMQ is reachable")
  public void rabbitMqIsReachable() {
    assertNotNull(rabbitProbe, "RabbitMQ probe was not executed");
    assertTrue(rabbitProbe.reachable(), () -> "RabbitMQ not reachable at " + endpoints.rabbitMqUri()
        + ": " + Optional.ofNullable(rabbitProbe.error()).orElse("no additional diagnostics"));
  }

  @Then("the UI proxy reports ok")
  public void theUiProxyReportsOk() {
    assertNotNull(uiHealth, "UI proxy probe was not executed");
    assertEquals(200, uiHealth.statusCode(), () -> "UI proxy /healthz returned " + uiHealth.statusCode()
        + " body=" + uiHealth.summary());
    String body = Optional.ofNullable(uiHealth.body()).map(String::trim).orElse("");
    assertTrue("ok".equalsIgnoreCase(body), () -> "Expected UI /healthz to return 'ok' but was '"
        + body + "'");
  }

  @When("I query the default swarm state")
  public void iQueryTheDefaultSwarmState() {
    String swarmId = endpoints.defaultSwarmId();
    defaultSwarmQuery = querySwarmState(swarmId);
    LOGGER.info("Swarm lookup {} -> {}", defaultSwarmQuery.uri(), defaultSwarmQuery.summary());
  }

  @Then("the default swarm is not registered")
  public void theDefaultSwarmIsNotRegistered() {
    assertNotNull(defaultSwarmQuery, "Swarm lookup was not executed");
    assertEquals(404, defaultSwarmQuery.statusCode(), () -> "Expected no swarm to exist immediately after deployment, but "
        + "received status " + defaultSwarmQuery.statusCode() + " body=" + defaultSwarmQuery.summary());
  }

  private void assertActuatorUp(String serviceName, HttpProbeResult result) {
    assertNotNull(result, serviceName + " health probe was not executed");
    assertEquals(200, result.statusCode(), () -> serviceName + " /actuator/health returned " + result.statusCode()
        + " body=" + result.summary());
    String status = actuatorStatus(result);
    assertTrue("UP".equalsIgnoreCase(status), () -> serviceName + " health status expected 'UP' but was '"
        + status + "' body=" + result.summary());
  }

  private String actuatorStatus(HttpProbeResult result) {
    String body = result.body();
    if (body == null || body.isBlank()) {
      return "";
    }
    try {
      JsonNode node = OBJECT_MAPPER.readTree(body);
      return Optional.ofNullable(node.path("status").asText(null)).orElse("");
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to parse health response from " + result.uri() + ": " + result.summary(), ex);
    }
  }

  private HttpProbeResult probe(WebClient client, URI base, String path) {
    URI target = base.resolve(path);
    try {
      ResponseEntity<String> entity = client.get()
          .uri(path)
          .accept(MediaType.ALL)
          .retrieve()
          .toEntity(String.class)
          .timeout(HTTP_TIMEOUT)
          .block(HTTP_TIMEOUT);
      return new HttpProbeResult(target, entity.getStatusCode().value(), entity.getBody());
    } catch (WebClientResponseException ex) {
      return new HttpProbeResult(target, ex.getRawStatusCode(), ex.getResponseBodyAsString());
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to call " + target, ex);
    }
  }

  private HttpProbeResult querySwarmState(String swarmId) {
    String path = "/api/swarms/" + swarmId;
    URI target = orchestratorBaseUrl.resolve(path);
    try {
      ResponseEntity<String> entity = orchestratorClient.webClient()
          .get()
          .uri(uriBuilder -> uriBuilder.path(path).build())
          .accept(MediaType.APPLICATION_JSON)
          .retrieve()
          .toEntity(String.class)
          .timeout(HTTP_TIMEOUT)
          .block(HTTP_TIMEOUT);
      return new HttpProbeResult(target, entity.getStatusCode().value(), entity.getBody());
    } catch (WebClientResponseException ex) {
      return new HttpProbeResult(target, ex.getRawStatusCode(), ex.getResponseBodyAsString());
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to query swarm state at " + target, ex);
    }
  }

  private RabbitProbeResult probeRabbit() {
    Connection connection = null;
    try {
      connection = rabbitSubscriptions.connectionFactory().createConnection();
      boolean open = connection != null && connection.isOpen();
      return new RabbitProbeResult(open, null);
    } catch (Exception ex) {
      return new RabbitProbeResult(false, ex.getMessage());
    } finally {
      if (connection != null) {
        try {
          connection.close();
        } catch (Exception closeEx) {
          LOGGER.debug("Failed to close RabbitMQ probe connection cleanly", closeEx);
        }
      }
    }
  }

  private record HttpProbeResult(URI uri, int statusCode, String body) {
    String summary() {
      String snippet = body == null ? "" : body.strip();
      if (snippet.length() > 200) {
        snippet = snippet.substring(0, 200) + "…";
      }
      return "status=" + statusCode + " body=" + snippet;
    }
  }

  private record RabbitProbeResult(boolean reachable, String error) {
    String summary() {
      return reachable ? "reachable" : "unreachable: " + Optional.ofNullable(error).orElse("unknown error");
    }
  }
}
