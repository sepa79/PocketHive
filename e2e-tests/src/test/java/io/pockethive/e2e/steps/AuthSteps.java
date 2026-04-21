package io.pockethive.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
  private static final UUID BUNDLE_RUNNER_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
  private static final UUID FOLDER_ADMIN_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

  private ServiceEndpoints endpoints;
  private AuthServiceClient authServiceClient;
  private AuthServiceClient adminAuthServiceClient;
  private OrchestratorClient adminOrchestratorClient;
  private OrchestratorClient activeOrchestratorClient;
  private ScenarioManagerClient activeScenarioManagerClient;
  private WebClient rawOrchestratorWebClient;
  private WebClient rawScenarioManagerWebClient;
  private String activeUsername;
  private int latestStatus;
  private String latestErrorBody;
  private List<TemplateSummary> latestTemplates = List.of();
  private AuthServiceClient.AuthenticatedUser latestAuthUser;
  private final List<String> swarmsToCleanup = new ArrayList<>();
  private final Map<String, String> resolvedSwarmIds = new HashMap<>();

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
    adminAuthServiceClient = AuthServiceClient.create(endpoints.auth().authServiceBaseUrl(), adminToken);
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

  @When("I load the current auth profile")
  public void iLoadTheCurrentAuthProfile() {
    ensureHarness();
    latestAuthUser = AuthServiceClient.create(endpoints.auth().authServiceBaseUrl(),
        authServiceClient.devLogin(activeUsername)).me();
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

  @Then("the runnable template ids are exactly")
  public void theRunnableTemplateIdsAreExactly(io.cucumber.datatable.DataTable table) {
    List<String> expected = table.asList();
    List<String> actual = latestTemplates.stream()
        .map(TemplateSummary::id)
        .toList();
    assertEquals(expected, actual, () -> "Unexpected runnable template ids for " + activeUsername + ": " + actual);
  }

  @When("I try to create swarm {string} from template {string}")
  public void iTryToCreateSwarmFromTemplate(String swarmId, String templateId) {
    ensureHarness();
    latestErrorBody = null;
    String resolvedSwarmId = resolveSwarmId(swarmId);
    try {
      ControlResponse response = activeOrchestratorClient.createSwarm(
          resolvedSwarmId,
          new SwarmCreateRequest(
              templateId,
              nextIdempotencyKey(resolvedSwarmId, "create"),
              "auth e2e",
              null,
              null,
              null,
              "DIRECT",
              null));
      latestStatus = 202;
      if (response != null) {
        swarmsToCleanup.add(resolvedSwarmId);
      }
    } catch (WebClientResponseException ex) {
      latestStatus = ex.getStatusCode().value();
      latestErrorBody = ex.getResponseBodyAsString();
    }
  }

  @When("I wait until swarm {string} becomes visible")
  public void iWaitUntilSwarmBecomesVisible(String swarmId) {
    ensureHarness();
    String resolvedSwarmId = resolveSwarmId(swarmId);
    Awaitility.await("swarm visible " + swarmId)
        .atMost(SWARM_REGISTRATION_TIMEOUT)
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> activeOrchestratorClient.findSwarm(resolvedSwarmId).isPresent());
  }

  @When("I try to stop swarm {string}")
  public void iTryToStopSwarm(String swarmId) {
    ensureHarness();
    latestErrorBody = null;
    String resolvedSwarmId = resolveSwarmId(swarmId);
    try {
      activeOrchestratorClient.stopSwarm(
          resolvedSwarmId,
          new ControlRequest(nextIdempotencyKey(resolvedSwarmId, "stop"), "auth e2e stop"));
      latestStatus = 202;
    } catch (WebClientResponseException ex) {
      latestStatus = ex.getStatusCode().value();
      latestErrorBody = ex.getResponseBodyAsString();
    }
  }

  @When("I try to remove swarm {string}")
  public void iTryToRemoveSwarm(String swarmId) {
    ensureHarness();
    latestErrorBody = null;
    String resolvedSwarmId = resolveSwarmId(swarmId);
    try {
      activeOrchestratorClient.removeSwarm(
          resolvedSwarmId,
          new ControlRequest(nextIdempotencyKey(resolvedSwarmId, "remove"), "auth e2e remove"));
      latestStatus = 202;
      swarmsToCleanup.remove(resolvedSwarmId);
    } catch (WebClientResponseException ex) {
      latestStatus = ex.getStatusCode().value();
      latestErrorBody = ex.getResponseBodyAsString();
    }
  }

  @Given("the admin provisions a bundle runner user")
  public void theAdminProvisionsABundleRunnerUser() {
    ensureHarness();
    adminAuthServiceClient.upsertUser(BUNDLE_RUNNER_ID, "local-bundle-runner", "Local Bundle Runner", true);
    adminAuthServiceClient.replaceGrants(BUNDLE_RUNNER_ID, List.of(
        grant("POCKETHIVE", "VIEW", "PH_DEPLOYMENT", "*"),
        grant("POCKETHIVE", "RUN", "PH_BUNDLE", "e2e/local-rest")));
  }

  @Given("the admin provisions an e2e folder admin user")
  public void theAdminProvisionsAnE2eFolderAdminUser() {
    ensureHarness();
    adminAuthServiceClient.upsertUser(FOLDER_ADMIN_ID, "local-e2e-folder-admin", "Local E2E Folder Admin", true);
    adminAuthServiceClient.replaceGrants(FOLDER_ADMIN_ID, List.of(
        grant("POCKETHIVE", "VIEW", "PH_DEPLOYMENT", "*"),
        grant("POCKETHIVE", "ALL", "PH_FOLDER", "e2e")));
  }

  @Then("the current auth profile contains grant {string} on {string}={string}")
  public void theCurrentAuthProfileContainsGrant(String permission, String resourceType, String resourceSelector) {
    assertNotNull(latestAuthUser, "Expected current auth profile to be loaded");
    boolean match = Optional.ofNullable(latestAuthUser.grants()).orElse(List.of()).stream()
        .anyMatch(grant -> Objects.equals(grant.permission(), permission)
            && Objects.equals(grant.resourceType(), resourceType)
            && Objects.equals(grant.resourceSelector(), resourceSelector));
    assertTrue(match, () -> "Grant not found in current profile: " + latestAuthUser.grants());
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
      resolvedSwarmIds.clear();
      return;
    }
    List<String> pending = new ArrayList<>(swarmsToCleanup);
    swarmsToCleanup.clear();
    for (String swarmId : pending) {
      try {
        adminOrchestratorClient.removeSwarm(
            swarmId,
            new ControlRequest(nextIdempotencyKey(swarmId, "cleanup"), "auth e2e cleanup"));
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
    resolvedSwarmIds.clear();
  }

  private void authenticateAs(String username) {
    activeUsername = Objects.requireNonNull(username, "username");
    String bearerToken = authServiceClient.devLogin(username);
    activeOrchestratorClient = OrchestratorClient.create(endpoints.orchestratorBaseUrl(), bearerToken);
    activeScenarioManagerClient = ScenarioManagerClient.create(endpoints.scenarioManagerBaseUrl(), bearerToken);
    latestAuthUser = null;
  }

  private String resolveSwarmId(String alias) {
    String normalizedAlias = Objects.requireNonNull(alias, "alias").trim();
    return resolvedSwarmIds.computeIfAbsent(normalizedAlias,
        key -> "%s-%s".formatted(key, UUID.randomUUID().toString().substring(0, 8)));
  }

  private String nextIdempotencyKey(String swarmId, String action) {
    return "%s-%s-%s".formatted(swarmId, action, UUID.randomUUID());
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

  private AuthServiceClient.AuthGrant grant(String product, String permission, String resourceType, String resourceSelector) {
    return new AuthServiceClient.AuthGrant(product, permission, resourceType, resourceSelector);
  }
}
