package io.pockethive.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.pockethive.e2e.clients.AuthServiceClient;
import io.pockethive.e2e.clients.OrchestratorClient;
import io.pockethive.e2e.clients.OrchestratorClient.ControlRequest;
import io.pockethive.e2e.clients.OrchestratorClient.ControlResponse;
import io.pockethive.e2e.clients.OrchestratorClient.SwarmCreateRequest;
import io.pockethive.e2e.clients.ScenarioManagerClient;
import io.pockethive.e2e.clients.ScenarioManagerClient.TemplateSummary;
import io.pockethive.e2e.config.EnvironmentConfig;
import io.pockethive.e2e.config.EnvironmentConfig.ServiceEndpoints;

/**
 * Auth-focused acceptance checks for current user permissions and scoped template access.
 */
public class AuthSteps {

  private static final Logger LOGGER = LoggerFactory.getLogger(AuthSteps.class);
  private static final Duration SWARM_REMOVE_TIMEOUT = Duration.ofSeconds(45);
  private static final Duration SWARM_REGISTRATION_TIMEOUT = Duration.ofSeconds(20);

  private ServiceEndpoints endpoints;
  private AuthServiceClient authServiceClient;
  private OrchestratorClient adminOrchestratorClient;
  private OrchestratorClient activeOrchestratorClient;
  private ScenarioManagerClient activeScenarioManagerClient;
  private WebClient rawOrchestratorWebClient;
  private WebClient rawScenarioManagerWebClient;
  private String activeUsername;
  private int latestStatus;
  private String latestErrorBody;
  private List<TemplateSummary> latestTemplates = List.of();
  private final List<String> swarmsToCleanup = new ArrayList<>();

  @Given("the auth harness is initialised")
  public void theAuthHarnessIsInitialised() {
    try {
      endpoints = EnvironmentConfig.loadServiceEndpoints();
    } catch (IllegalStateException ex) {
      Assumptions.assumeTrue(false, () -> "Skipping auth checks: " + ex.getMessage());
      return;
    }

    authServiceClient = AuthServiceClient.create(endpoints.auth().authServiceBaseUrl());
    String adminToken = endpoints.auth().accessToken()
        .orElseGet(() -> authServiceClient.devLogin(endpoints.auth().username()));
    adminOrchestratorClient = OrchestratorClient.create(endpoints.orchestratorBaseUrl(), adminToken);
    rawOrchestratorWebClient = WebClient.builder().baseUrl(endpoints.orchestratorBaseUrl().toString()).build();
    rawScenarioManagerWebClient = WebClient.builder().baseUrl(endpoints.scenarioManagerBaseUrl().toString()).build();
    authenticateAs(endpoints.auth().username());
  }

  @Given("I authenticate as {string}")
  public void iAuthenticateAs(String username) {
    ensureHarness();
    authenticateAs(username);
  }

  @When("I call protected PocketHive APIs without credentials")
  public void iCallProtectedPocketHiveApisWithoutCredentials() {
    ensureHarness();
    latestStatus = getStatus(rawScenarioManagerWebClient, "/templates");
    assertEquals(401, latestStatus, "Scenario Manager should reject unauthenticated /templates");
    latestStatus = getStatus(rawOrchestratorWebClient, "/api/swarms");
    assertEquals(401, latestStatus, "Orchestrator should reject unauthenticated /api/swarms");
  }

  @Then("Scenario Manager and Orchestrator reject unauthenticated access")
  public void scenarioManagerAndOrchestratorRejectUnauthenticatedAccess() {
    ensureHarness();
  }

  @When("I list runnable templates for the active user")
  public void iListRunnableTemplatesForTheActiveUser() {
    ensureHarness();
    latestTemplates = activeScenarioManagerClient.listTemplates();
  }

  @Then("the runnable template list is empty")
  public void theRunnableTemplateListIsEmpty() {
    assertTrue(latestTemplates.isEmpty(), () -> "Expected no runnable templates for " + activeUsername
        + " but got " + latestTemplates.size());
  }

  @Then("all runnable templates are under folder {string}")
  public void allRunnableTemplatesAreUnderFolder(String folderPath) {
    assertFalse(latestTemplates.isEmpty(), () -> "Expected at least one runnable template for " + activeUsername);
    boolean allMatch = latestTemplates.stream()
        .allMatch(template -> folderMatches(folderPath, template.folderPath()));
    assertTrue(allMatch, () -> "Found template outside folder " + folderPath + ": " + latestTemplates);
  }

  @When("I try to create swarm {string} from template {string}")
  public void iTryToCreateSwarmFromTemplate(String swarmId, String templateId) {
    ensureHarness();
    latestErrorBody = null;
    try {
      ControlResponse response = activeOrchestratorClient.createSwarm(
          swarmId,
          new SwarmCreateRequest(templateId, swarmId + "-idem", "auth e2e", null, null, null, "DIRECT", null));
      latestStatus = 202;
      if (response != null) {
        swarmsToCleanup.add(swarmId);
      }
    } catch (WebClientResponseException ex) {
      latestStatus = ex.getStatusCode().value();
      latestErrorBody = ex.getResponseBodyAsString();
    }
  }

  @Then("the create request is accepted")
  public void theCreateRequestIsAccepted() {
    assertEquals(202, latestStatus, () -> "Expected create request to be accepted but got " + latestStatus
        + " body=" + latestErrorBody);
  }

  @Then("the create request is rejected with status {int}")
  public void theCreateRequestIsRejectedWithStatus(int expectedStatus) {
    assertEquals(expectedStatus, latestStatus, () -> "Unexpected create response status body=" + latestErrorBody);
  }

  @After
  public void cleanupSwarms() {
    if (adminOrchestratorClient == null || swarmsToCleanup.isEmpty()) {
      return;
    }
    List<String> pending = new ArrayList<>(swarmsToCleanup);
    swarmsToCleanup.clear();
    for (String swarmId : pending) {
      if (!awaitSwarmRegistrationForCleanup(swarmId)) {
        continue;
      }
      try {
        adminOrchestratorClient.removeSwarm(swarmId, new ControlRequest(swarmId + "-cleanup", "auth e2e cleanup"));
      } catch (WebClientResponseException.NotFound ignored) {
        continue;
      } catch (WebClientResponseException ex) {
        if (ex.getStatusCode().value() != 409) {
          throw ex;
        }
      }
      Awaitility.await("swarm removal " + swarmId)
          .atMost(SWARM_REMOVE_TIMEOUT)
          .pollInterval(Duration.ofSeconds(2))
          .until(() -> adminOrchestratorClient.findSwarm(swarmId).isEmpty());
    }
  }

  private boolean awaitSwarmRegistrationForCleanup(String swarmId) {
    try {
      // Temporary E2E workaround: wait for swarm registration before cleanup remove.
      // Remove after the control-plane command lifecycle gains explicit receipt acknowledgement.
      Awaitility.await("swarm registration before cleanup " + swarmId)
          .atMost(SWARM_REGISTRATION_TIMEOUT)
          .pollInterval(Duration.ofSeconds(1))
          .until(() -> adminOrchestratorClient.findSwarm(swarmId).isPresent());
      return true;
    } catch (org.awaitility.core.ConditionTimeoutException ex) {
      LOGGER.warn("Skipping cleanup remove for swarm {} because it never became visible before cleanup", swarmId);
      return false;
    }
  }

  private void authenticateAs(String username) {
    activeUsername = Objects.requireNonNull(username, "username");
    String bearerToken = authServiceClient.devLogin(username);
    activeOrchestratorClient = OrchestratorClient.create(endpoints.orchestratorBaseUrl(), bearerToken);
    activeScenarioManagerClient = ScenarioManagerClient.create(endpoints.scenarioManagerBaseUrl(), bearerToken);
  }

  private int getStatus(WebClient client, String path) {
    try {
      return client.get()
          .uri(path)
          .retrieve()
          .toBodilessEntity()
          .block()
          .getStatusCode()
          .value();
    } catch (WebClientResponseException ex) {
      return ex.getStatusCode().value();
    }
  }

  private boolean folderMatches(String expectedFolder, String actualFolder) {
    if (expectedFolder == null || actualFolder == null) {
      return false;
    }
    String expected = expectedFolder.trim().toLowerCase(Locale.ROOT);
    String actual = actualFolder.trim().toLowerCase(Locale.ROOT);
    return actual.equals(expected) || actual.startsWith(expected + "/");
  }

  private void ensureHarness() {
    Assumptions.assumeTrue(endpoints != null, "Auth harness was not initialised");
  }
}
