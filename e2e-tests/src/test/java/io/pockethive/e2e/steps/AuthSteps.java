package io.pockethive.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Assumptions;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.pockethive.e2e.clients.AuthServiceClient;
import io.pockethive.e2e.clients.OrchestratorClient;
import io.pockethive.swarm.model.lifecycle.ControlResponse;
import io.pockethive.swarm.model.lifecycle.ControlRequest;
import io.pockethive.swarm.model.lifecycle.SwarmCreateRequest;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.e2e.clients.ScenarioManagerClient;
import io.pockethive.e2e.clients.ScenarioManagerClient.TemplateSummary;
import io.pockethive.e2e.config.EnvironmentConfig;
import io.pockethive.e2e.config.EnvironmentConfig.ServiceEndpoints;
import io.pockethive.e2e.support.api.ApiPlaceholderResolver;
import io.pockethive.e2e.support.api.ApiResponse;
import io.pockethive.e2e.support.api.ApiService;
import io.pockethive.e2e.support.api.IngressApiDriver;
import io.pockethive.e2e.support.auth.AuthRolloutFixtures;

/**
 * Auth-focused acceptance checks for current user permissions and scoped template access.
 */
public class AuthSteps {

  private static final Duration SWARM_REMOVE_TIMEOUT = Duration.ofSeconds(45);
  private static final Duration SWARM_REGISTRATION_TIMEOUT = Duration.ofSeconds(20);

  private ServiceEndpoints endpoints;
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private ApiPlaceholderResolver placeholderResolver;
  private IngressApiDriver apiDriver;
  private AuthRolloutFixtures authFixtures;
  private AuthServiceClient authServiceClient;
  private AuthServiceClient adminAuthServiceClient;
  private OrchestratorClient adminOrchestratorClient;
  private OrchestratorClient activeOrchestratorClient;
  private ScenarioManagerClient activeScenarioManagerClient;
  private String activeUsername;
  private String activeBearerToken;
  private int latestStatus;
  private String latestErrorBody;
  private JsonNode latestResponseJson;
  private List<TemplateSummary> latestTemplates = List.of();
  private AuthServiceClient.AuthenticatedUser latestAuthUser;
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
    adminAuthServiceClient = AuthServiceClient.create(endpoints.auth().authServiceBaseUrl(), adminToken);
    authFixtures = new AuthRolloutFixtures(adminAuthServiceClient);
    adminOrchestratorClient = OrchestratorClient.create(endpoints.orchestratorBaseUrl(), adminToken);
    apiDriver = new IngressApiDriver(endpoints, objectMapper);
    placeholderResolver = new ApiPlaceholderResolver();
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
    latestStatus = apiDriver.getStatus(ApiService.SCENARIO_MANAGER, "/templates", null);
    assertEquals(401, latestStatus, "Scenario Manager should reject unauthenticated /templates");
    latestStatus = apiDriver.getStatus(ApiService.ORCHESTRATOR, "/api/swarms", null);
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
    String resolvedSwarmId = placeholderResolver.resolveSwarmId(swarmId);
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
              NetworkMode.DIRECT,
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
    String resolvedSwarmId = placeholderResolver.resolveSwarmId(swarmId);
    Awaitility.await("swarm visible " + swarmId)
        .atMost(SWARM_REGISTRATION_TIMEOUT)
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> activeOrchestratorClient.findSwarm(resolvedSwarmId).isPresent());
  }

  @When("I try to stop swarm {string}")
  public void iTryToStopSwarm(String swarmId) {
    ensureHarness();
    latestErrorBody = null;
    String resolvedSwarmId = placeholderResolver.resolveSwarmId(swarmId);
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

  @When("I try to start swarm {string}")
  public void iTryToStartSwarm(String swarmId) {
    ensureHarness();
    latestErrorBody = null;
    String resolvedSwarmId = placeholderResolver.resolveSwarmId(swarmId);
    try {
      activeOrchestratorClient.startSwarm(
          resolvedSwarmId,
          new ControlRequest(nextIdempotencyKey(resolvedSwarmId, "start"), "auth e2e start"));
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
    String resolvedSwarmId = placeholderResolver.resolveSwarmId(swarmId);
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

  @When("I call {string} {string} {string} without credentials")
  public void iCallApiWithoutCredentials(String service, String method, String path) {
    ensureHarness();
    executeRawCall(service, method, path, null, MediaType.APPLICATION_JSON, false);
  }

  @When("I call {string} {string} {string} for the active user")
  public void iCallApiForTheActiveUser(String service, String method, String path) {
    ensureHarness();
    executeRawCall(service, method, path, null, MediaType.APPLICATION_JSON, true);
  }

  @When("I call {string} {string} {string} for the active user with body")
  public void iCallApiForTheActiveUserWithBody(String service, String method, String path, String body) {
    ensureHarness();
    executeRawCall(service, method, path, body, MediaType.APPLICATION_JSON, true);
  }

  @When("I call {string} {string} {string} for the active user with {string} body")
  public void iCallApiForTheActiveUserWithTypedBody(String service,
                                                    String method,
                                                    String path,
                                                    String contentType,
                                                    String body) {
    ensureHarness();
    executeRawCall(service, method, path, body, MediaType.parseMediaType(contentType), true);
  }

  @When("I fetch the swarm snapshot for {string} via the API")
  public void iFetchTheSwarmSnapshotViaTheApi(String swarmAlias) {
    ensureHarness();
    executeRawCall("Orchestrator",
        "GET",
        "/api/swarms/{{swarm:%s}}".formatted(swarmAlias),
        null,
        MediaType.APPLICATION_JSON,
        true);
  }

  @When("I wait until swarm journal runs are available for {string}")
  public void iWaitUntilSwarmJournalRunsAreAvailable(String swarmAlias) {
    ensureHarness();
    String resolvedPath = "/api/swarms/{{swarm:%s}}/journal/runs".formatted(swarmAlias);
    Awaitility.await("journal runs for " + swarmAlias)
        .atMost(SWARM_REGISTRATION_TIMEOUT)
        .pollInterval(Duration.ofSeconds(1))
        .until(() -> {
          executeRawCall("Orchestrator", "GET", resolvedPath, null, MediaType.APPLICATION_JSON, true);
          return latestStatus == 200 && latestResponseJson != null && latestResponseJson.isArray() && latestResponseJson.size() > 0;
        });
  }

  @When("I remember the last response value at JSON pointer {string} as {string}")
  public void iRememberTheLastResponseValueAtJsonPointerAs(String jsonPointer, String key) {
    assertNotNull(latestResponseJson, "Expected the last API response to contain JSON");
    JsonNode value = latestResponseJson.at(jsonPointer);
    assertFalse(value.isMissingNode() || value.isNull(),
        () -> "JSON pointer %s was not found in the last response".formatted(jsonPointer));
    placeholderResolver.rememberValue(key, value.isValueNode() ? value.asText() : value.toString());
  }

  @Given("I remember a unique value with prefix {string} as {string}")
  public void iRememberAUniqueValueWithPrefixAs(String prefix, String key) {
    placeholderResolver.rememberUniqueValue(prefix, key);
  }

  @Then("the API response status is {int}")
  public void theApiResponseStatusIs(int expectedStatus) {
    assertEquals(expectedStatus, latestStatus, () -> "Unexpected API status body=" + latestErrorBody);
  }

  @Given("the admin provisions a bundle runner user")
  public void theAdminProvisionsABundleRunnerUser() {
    ensureHarness();
    authFixtures.provisionBundleRunner();
  }

  @Given("the admin provisions an e2e folder admin user")
  public void theAdminProvisionsAnE2eFolderAdminUser() {
    ensureHarness();
    authFixtures.provisionE2eFolderAdmin();
  }

  @Given("the admin provisions a bundles folder admin user")
  public void theAdminProvisionsABundlesFolderAdminUser() {
    ensureHarness();
    authFixtures.provisionBundlesFolderAdmin();
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
      if (placeholderResolver != null) {
        placeholderResolver.clear();
      }
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
    placeholderResolver.clear();
  }

  private void authenticateAs(String username) {
    activeUsername = Objects.requireNonNull(username, "username");
    activeBearerToken = authServiceClient.devLogin(username);
    activeOrchestratorClient = OrchestratorClient.create(endpoints.orchestratorBaseUrl(), activeBearerToken);
    activeScenarioManagerClient = ScenarioManagerClient.create(endpoints.scenarioManagerBaseUrl(), activeBearerToken);
    latestAuthUser = null;
  }

  private String nextIdempotencyKey(String swarmId, String action) {
    return "%s-%s-%s".formatted(swarmId, action, UUID.randomUUID());
  }

  private void executeRawCall(String service,
                              String method,
                              String path,
                              String body,
                              MediaType contentType,
                              boolean authenticated) {
    String resolvedPath = placeholderResolver.resolveTemplate(path);
    String resolvedBody = body == null ? null : placeholderResolver.resolveTemplate(body);
    ApiResponse response = apiDriver.execute(
        ApiService.fromDisplayName(service),
        method,
        resolvedPath,
        resolvedBody,
        contentType,
        authenticated ? activeBearerToken : null);
    latestStatus = response.status();
    latestErrorBody = response.body();
    latestResponseJson = response.jsonBody();
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
