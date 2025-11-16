package io.pockethive.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.cucumber.java.After;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.pockethive.control.CommandTarget;
import io.pockethive.control.ErrorConfirmation;
import io.pockethive.control.ReadyConfirmation;
import io.pockethive.e2e.clients.OrchestratorClient;
import io.pockethive.e2e.clients.OrchestratorClient.ComponentConfigRequest;
import io.pockethive.e2e.clients.OrchestratorClient.ControlRequest;
import io.pockethive.e2e.clients.OrchestratorClient.ControlResponse;
import io.pockethive.e2e.clients.OrchestratorClient.SwarmCreateRequest;
import io.pockethive.e2e.clients.OrchestratorClient.SwarmView;
import io.pockethive.e2e.clients.RabbitManagementClient;
import io.pockethive.e2e.clients.RabbitSubscriptions;
import io.pockethive.e2e.clients.ScenarioManagerClient;
import io.pockethive.e2e.clients.ScenarioManagerClient.ScenarioDetails;
import io.pockethive.e2e.clients.ScenarioManagerClient.ScenarioSummary;
import io.pockethive.e2e.config.EnvironmentConfig;
import io.pockethive.e2e.config.EnvironmentConfig.ControlPlaneSettings;
import io.pockethive.e2e.config.EnvironmentConfig.ServiceEndpoints;
import io.pockethive.e2e.support.ControlPlaneEvents;
import io.pockethive.e2e.support.QueueProbe;
import io.pockethive.e2e.support.SwarmAssertions;
import io.pockethive.e2e.support.StatusEvent;
import io.pockethive.e2e.support.WorkQueueConsumer;
import io.pockethive.controlplane.spring.ControlPlaneTopologyDescriptorFactory;
import io.pockethive.controlplane.topology.ControlPlaneRouteCatalog;
import io.pockethive.controlplane.topology.ControlPlaneTopologyDescriptor;
import io.pockethive.controlplane.topology.ControlPlaneTopologySettings;
import io.pockethive.controlplane.topology.ControlQueueDescriptor;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.swarm.model.Work;

/**
 * Step definitions for the Phase 2 swarm lifecycle golden path scenario.
 */
public class SwarmLifecycleSteps {

  private static final Logger LOGGER = LoggerFactory.getLogger(SwarmLifecycleSteps.class);
  private static final String GENERATOR_ROLE = "generator";
  private static final String MODERATOR_ROLE = "moderator";
  private static final String PROCESSOR_ROLE = "processor";
  private static final String POSTPROCESSOR_ROLE = "postprocessor";

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private ServiceEndpoints endpoints;
  private OrchestratorClient orchestratorClient;
  private ScenarioManagerClient scenarioManagerClient;
  private RabbitSubscriptions rabbitSubscriptions;
  private RabbitManagementClient rabbitManagementClient;
  private ControlPlaneEvents controlPlaneEvents;
  private ControlPlaneSettings controlPlane;
  private ScenarioDetails scenarioDetails;
  private SwarmTemplate template;
  private String swarmId;
  private String idempotencyPrefix;
  private boolean swarmRemoved;

  private ControlResponse createResponse;
  private ControlResponse startResponse;
  private ControlResponse stopResponse;
  private ControlResponse removeResponse;
  private ControlResponse generatorConfigResponse;

  private final Map<String, StatusEvent> workerStatusByRole = new LinkedHashMap<>();
  private final Map<String, String> workerInstances = new LinkedHashMap<>();
  private final Map<String, String> roleAliasMap = new LinkedHashMap<>();
  private boolean roleAliasesInitialised;
  private boolean workerStatusesCaptured;
  private WorkQueueConsumer workQueueConsumer;
  private String tapQueueName;

  @Given("the swarm lifecycle harness is initialised")
  public void theSwarmLifecycleHarnessIsInitialised() {
    try {
      endpoints = EnvironmentConfig.loadServiceEndpoints();
    } catch (IllegalStateException ex) {
      Assumptions.assumeTrue(false, () -> "Skipping lifecycle scenario: " + ex.getMessage());
    }

    orchestratorClient = OrchestratorClient.create(endpoints.orchestratorBaseUrl());
    scenarioManagerClient = ScenarioManagerClient.create(endpoints.scenarioManagerBaseUrl());
    controlPlane = endpoints.controlPlane();
    rabbitSubscriptions = RabbitSubscriptions.from(endpoints.rabbitMq(), controlPlane);
    rabbitManagementClient = RabbitManagementClient.create(endpoints.rabbitMq());
    controlPlaneEvents = rabbitSubscriptions.controlPlaneEvents();

    String baseSwarmId = endpoints.defaultSwarmId();
    String suffix = Long.toHexString(System.nanoTime());
    swarmId = baseSwarmId + "-gp-" + suffix;
    idempotencyPrefix = endpoints.idempotencyKeyPrefix();

    LOGGER.info("Lifecycle harness initialised with swarmId={} endpoints={} ", swarmId, endpoints.asMap());
  }

  @And("a default scenario template is available")
  public void aDefaultScenarioTemplateIsAvailable() {
    ensureHarness();
    List<ScenarioSummary> summaries;
    try {
      summaries = scenarioManagerClient.listScenarios();
    } catch (Exception ex) {
      Assumptions.assumeTrue(false, () -> "Skipping lifecycle scenario: failed to query Scenario Manager: " + ex.getMessage());
      return;
    }
    Assumptions.assumeTrue(!summaries.isEmpty(), "Skipping lifecycle scenario: no scenarios available");

    ScenarioSummary summary = summaries.getFirst();
    LOGGER.info("Using scenario {} - {}", summary.id(), summary.name());
    scenarioDetails = scenarioManagerClient.fetchScenario(summary.id());
    template = Objects.requireNonNull(scenarioDetails.template(), "scenario template");
    resetRoleAliases();
    workerStatusesCaptured = false;
    workerStatusByRole.clear();
    workerInstances.clear();
    logScenarioTemplate("default listing");
  }

  @And("the {string} scenario template is requested")
  public void theScenarioTemplateIsRequested(String templateId) {
    ensureHarness();
    Objects.requireNonNull(templateId, "templateId");
    String trimmed = templateId.trim();
    Assumptions.assumeTrue(!trimmed.isEmpty(), "Skipping lifecycle scenario: template id must not be blank");
    try {
      scenarioDetails = scenarioManagerClient.fetchScenario(trimmed);
    } catch (Exception ex) {
      Assumptions.assumeTrue(false, () ->
          "Skipping lifecycle scenario: failed to fetch scenario %s: %s".formatted(trimmed, ex.getMessage()));
      return;
    }
    template = Objects.requireNonNull(scenarioDetails.template(), "scenario template");
    LOGGER.info("Using scenario {} - {}", scenarioDetails.id(), scenarioDetails.name());
    resetRoleAliases();
    workerStatusesCaptured = false;
    workerStatusByRole.clear();
    workerInstances.clear();
    logScenarioTemplate("explicit request");
  }

  @When("I create the swarm from that template")
  public void iCreateTheSwarmFromThatTemplate() {
    ensureTemplate();
    String idempotencyKey = idKey("create");
    SwarmCreateRequest request = new SwarmCreateRequest(scenarioDetails.id(), idempotencyKey, "e2e lifecycle create");
    createResponse = orchestratorClient.createSwarm(swarmId, request);
    LOGGER.info("Create request accepted correlation={} watch={}", createResponse.correlationId(), createResponse.watch());
  }

  @Then("the swarm is registered and queues are declared")
  public void theSwarmIsRegisteredAndQueuesAreDeclared() {
    ensureCreateResponse();

    awaitReady("swarm-create", createResponse);
    awaitReady("swarm-template", createResponse);
    assertNoErrors(createResponse.correlationId(), "swarm-create");
    assertWatchMatched(createResponse);

    SwarmAssertions.await("swarm registered", () -> {
      Optional<SwarmView> view = orchestratorClient.findSwarm(swarmId);
      assertTrue(view.isPresent(), "Swarm should be registered after create");
      assertEquals("READY", view.get().status(), "Swarm status should be READY after template applied");
    });

    QueueProbe probe = new QueueProbe(rabbitSubscriptions.connectionFactory());
    for (String suffix : expectedQueueSuffixes(template)) {
      String queueName = "ph." + swarmId + "." + suffix;
      assertTrue(probe.exists(queueName), () -> "Expected workload queue to exist: " + queueName);
    }
  }

  @When("I start the swarm")
  public void iStartTheSwarm() {
    ensureCreateResponse();
    String idempotencyKey = idKey("start");
    startResponse = orchestratorClient.startSwarm(swarmId, new ControlRequest(idempotencyKey, "e2e lifecycle start"));
    LOGGER.info("Start request correlation={} watch={}", startResponse.correlationId(), startResponse.watch());
  }

  @Then("the swarm reports running")
  public void theSwarmReportsRunning() {
    ensureStartResponse();
    awaitReady("swarm-start", startResponse);
    assertNoErrors(startResponse.correlationId(), "swarm-start");
    assertWatchMatched(startResponse);

    SwarmAssertions.await("swarm running", () -> {
      Optional<SwarmView> view = orchestratorClient.findSwarm(swarmId);
      assertTrue(view.isPresent(), "Swarm should be available when running");
      assertEquals("RUNNING", view.get().status(), "Swarm status should be RUNNING after start");
      assertTrue(view.get().workEnabled(), "Workloads should be enabled after start");
    });

    SwarmAssertions.await("all workers running", () -> {
      captureWorkerStatuses(true);
      for (String role : workerRoles()) {
        StatusEvent status = workerStatusByRole.get(role);
        assertWorkerRunning(role, status);
      }
    });
  }

  @And("I request a single generator run")
  public void iRequestASingleGeneratorRun() {
    ensureStartResponse();
    captureWorkerStatuses();
    ensureFinalQueueTap();
    String generatorKey = roleKey(GENERATOR_ROLE);
    String generatorInstance = generatorKey == null ? null : workerInstances.get(generatorKey);
    assertNotNull(generatorInstance, "Generator instance should be discovered from status snapshots");
    String generatorRoleName = actualRoleName(generatorKey != null ? generatorKey : GENERATOR_ROLE);

    Map<String, Object> patch = new LinkedHashMap<>();
    patch.put("enabled", true);
    patch.put("singleRequest", true);

    ComponentConfigRequest request = new ComponentConfigRequest(
        idKey("generator-single"),
        patch,
        "e2e generator single request",
        swarmId,
        CommandTarget.INSTANCE
    );

    generatorConfigResponse = orchestratorClient.updateComponentConfig(generatorRoleName, generatorInstance, request);
    LOGGER.info("Generator config-update requested for instance={} correlation={} ",
        generatorInstance, generatorConfigResponse.correlationId());

    awaitReady("config-update", generatorConfigResponse);
    assertNoErrors(generatorConfigResponse.correlationId(), "generator config-update");
    assertWatchMatched(generatorConfigResponse);

    SwarmAssertions.await("generator status delta", () -> {
      StatusEvent delta = controlPlaneEvents.latestStatusDeltaEvent(swarmId, generatorRoleName, generatorInstance)
          .orElseThrow(() -> new AssertionError("No status-delta captured for generator"));
      assertTrue("status-delta".equalsIgnoreCase(delta.kind()),
          () -> "Expected status-delta kind for generator but was " + delta.kind());
      Map<String, Object> snapshot = workerSnapshot(delta, generatorKey != null ? generatorKey : GENERATOR_ROLE);
      assertFalse(snapshot.isEmpty(), "Generator snapshot should include worker details");
      assertTrue(isTruthy(snapshot.get("enabled")), "Generator snapshot should report enabled=true");
      Map<String, Object> config = snapshotConfig(snapshot);
      assertFalse(config.isEmpty(), "Generator snapshot should include applied config");
      assertTrue(isTruthy(config.get("singleRequest")),
          () -> "Expected singleRequest=true in generator config but was " + config.get("singleRequest"));
    });
  }

  @And("the swarm worker statuses reflect the swarm topology")
  public void theSwarmWorkerStatusesReflectTheSwarmTopology() {
    captureWorkerStatuses();
    for (String role : workerRoles()) {
      assertWorkerTopology(role);
    }
  }

  @Then("the final queue receives the default generator response")
  public void theFinalQueueReceivesTheDefaultGeneratorResponse() throws Exception {
    ensureStartResponse();
    assertNotNull(generatorConfigResponse, "Generator config update was not issued");

    ensureFinalQueueTap();
    String queue = tapQueueName != null ? tapQueueName : finalQueueName();

    WorkQueueConsumer.Message message = workQueueConsumer.consumeNext(SwarmAssertions.defaultTimeout())
        .orElseThrow(() -> new AssertionError("No message observed on tap queue " + queue));

    try {
      JsonNode root = objectMapper.readTree(message.body());
      int statusCode = root.path("status").asInt();
      if (statusCode != 200) {
        LOGGER.warn("Final queue status code was {}. Body={} headers={}",
            statusCode, root, message.headers());
        assertEquals(200, statusCode, "Final queue response should report status 200");
      }
      JsonNode bodyNode = root.path("body");
      String bodyText = bodyNode.isMissingNode() ? message.bodyAsString() : bodyNode.asText();
      if (bodyText == null || bodyText.isBlank()) {
        bodyText = message.bodyAsString();
      }
      final String finalBodyText = bodyText;
      if (!finalBodyText.contains("default generator response")) {
        LOGGER.warn("Final queue payload did not contain default marker. payload={} headers={} rawBody={}",
            finalBodyText, message.headers(), message.bodyAsString());
      }
      if (looksLikeJson(finalBodyText)) {
        JsonNode parsedBody = objectMapper.readTree(finalBodyText);
        assertEquals("default generator response", parsedBody.path("message").asText(),
            "Generator response body should match WireMock default");
      }
      inspectObservabilityTrace(message);
    } finally {
      message.ack();
    }
  }

  @When("I stop the swarm")
  public void iStopTheSwarm() {
    ensureStartResponse();
    String idempotencyKey = idKey("stop");
    stopResponse = orchestratorClient.stopSwarm(swarmId, new ControlRequest(idempotencyKey, "e2e lifecycle stop"));
    LOGGER.info("Stop request correlation={} watch={}", stopResponse.correlationId(), stopResponse.watch());
  }

  @Then("the swarm reports stopped")
  public void theSwarmReportsStopped() {
    ensureStopResponse();
    awaitReady("swarm-stop", stopResponse);
    assertNoErrors(stopResponse.correlationId(), "swarm-stop");
    assertWatchMatched(stopResponse);

    SwarmAssertions.await("swarm stopped", () -> {
      Optional<SwarmView> view = orchestratorClient.findSwarm(swarmId);
      assertTrue(view.isPresent(), "Swarm should still be registered when stopped");
      assertEquals("STOPPED", view.get().status(), "Swarm status should be STOPPED after stop");
    });

    Optional<ReadyConfirmation> readyOpt = controlPlaneEvents.readyConfirmation("swarm-stop", stopResponse.correlationId());
    assertTrue(readyOpt.isPresent(),
        () -> "Missing ready confirmation for swarm-stop correlation=" + stopResponse.correlationId());
    ReadyConfirmation ready = readyOpt.get();
    assertNotNull(ready.state(), "Stop ready confirmation should include command state");
    assertEquals("Stopped", ready.state().status(), "Stop ready confirmation should report a Stopped state");
  }

  @When("I remove the swarm")
  public void iRemoveTheSwarm() {
    ensureStopResponse();
    String idempotencyKey = idKey("remove");
    removeResponse = orchestratorClient.removeSwarm(swarmId, new ControlRequest(idempotencyKey, "e2e lifecycle remove"));
    LOGGER.info("Remove request correlation={} watch={}", removeResponse.correlationId(), removeResponse.watch());
  }

  @Then("the swarm is removed and lifecycle confirmations are recorded")
  public void theSwarmIsRemovedAndLifecycleConfirmationsAreRecorded() {
    ensureRemoveResponse();
    awaitReady("swarm-remove", removeResponse);
    assertNoErrors(removeResponse.correlationId(), "swarm-remove");
    assertWatchMatched(removeResponse);
    swarmRemoved = true;

    SwarmAssertions.await("swarm removed", () -> {
      Optional<SwarmView> view = orchestratorClient.findSwarm(swarmId);
      assertTrue(view.isEmpty(), "Swarm should no longer be present after removal");
    });

    assertEquals(1, controlPlaneEvents.readyCount("swarm-create"), "Expected exactly one swarm-create ready event");
    assertEquals(1, controlPlaneEvents.readyCount("swarm-template"), "Expected exactly one swarm-template ready event");
    assertEquals(1, controlPlaneEvents.readyCount("swarm-start"), "Expected exactly one swarm-start ready event");
    assertEquals(1, controlPlaneEvents.readyCount("swarm-stop"), "Expected exactly one swarm-stop ready event");
    assertEquals(1, controlPlaneEvents.readyCount("swarm-remove"), "Expected exactly one swarm-remove ready event");
    assertTrue(controlPlaneEvents.errors().isEmpty(), "No error confirmations should be emitted during the golden path");
  }

  @After
  public void tearDownLifecycle() {
    if (workQueueConsumer != null) {
      try {
        workQueueConsumer.close();
      } catch (Exception ex) {
        LOGGER.debug("Failed to close work queue consumer", ex);
      } finally {
        workQueueConsumer = null;
        tapQueueName = null;
      }
    }
    if (controlPlaneEvents != null) {
      controlPlaneEvents.close();
    }
    if (!swarmRemoved && orchestratorClient != null && swarmId != null) {
      try {
        LOGGER.info("Attempting to remove swarm {} during cleanup", swarmId);
        orchestratorClient.removeSwarm(swarmId, new ControlRequest(idKey("cleanup"), "cleanup"));
      } catch (Exception ex) {
        LOGGER.warn("Cleanup remove failed for swarm {}", swarmId, ex);
      }
    }
  }

  private void captureWorkerStatuses() {
    captureWorkerStatuses(false);
  }

  private void captureWorkerStatuses(boolean forceRefresh) {
    if (workerStatusesCaptured && !forceRefresh) {
      return;
    }
    ensureTemplate();
    List<String> roles = workerRoles();
    SwarmAssertions.await("status-full events for swarm workers", () -> {
      List<ControlPlaneEvents.StatusEnvelope> statuses = controlPlaneEvents.statusesForSwarm(swarmId);
      for (String role : roles) {
        boolean present = statuses.stream()
            .anyMatch(env -> isStatusFullForRole(env.status(), role));
        String displayRole = actualRoleName(role);
        assertTrue(present, () -> "Missing status-full event for role " + displayRole);
      }
    });

    List<ControlPlaneEvents.StatusEnvelope> statuses = controlPlaneEvents.statusesForSwarm(swarmId);
    Map<String, StatusEvent> latestStatuses = new LinkedHashMap<>();
    Map<String, String> latestInstances = new LinkedHashMap<>();
    for (String role : roles) {
      ControlPlaneEvents.StatusEnvelope envelope = statuses.stream()
          .filter(env -> isStatusFullForRole(env.status(), role))
          .max(Comparator.comparing(ControlPlaneEvents.StatusEnvelope::receivedAt))
          .orElseThrow(() -> new AssertionError("No status-full captured for role " + role));
      StatusEvent status = envelope.status();
      latestStatuses.put(role, status);
      String instance = status.instance();
      String displayRole = actualRoleName(role);
      assertNotNull(instance, () -> "Status event for role " + displayRole + " should include an instance id");
      assertFalse(instance.isBlank(), () -> "Status event for role " + displayRole + " should include an instance id");
      latestInstances.put(role, instance);
      Map<String, Object> snapshot = workerSnapshot(status, role);
      LOGGER.info("Captured status-full for role={} instance={} details={}",
          displayRole, instance, describeStatus(status, snapshot));
    }
    workerStatusByRole.clear();
    workerStatusByRole.putAll(latestStatuses);
    workerInstances.clear();
    workerInstances.putAll(latestInstances);
    workerStatusesCaptured = true;
  }

  private void assertWorkerRunning(String role, StatusEvent status) {
    String displayRole = actualRoleName(role);
    assertNotNull(status, () -> "No status recorded for role " + displayRole);
    String instance = status.instance();
    Map<String, Object> snapshot = workerSnapshot(status, role);
    LOGGER.info("Latest status summary role={} instance={} details={}",
        displayRole, instance, describeStatus(status, snapshot));

    boolean aggregateEnabled = Boolean.TRUE.equals(status.enabled());
    assertTrue(aggregateEnabled,
        () -> "Aggregate enabled flag was false for role " + displayRole + ": " + describeStatus(status, snapshot));

    assertFalse(snapshot.isEmpty(),
        () -> "No worker snapshot available for role " + displayRole + ": " + describeStatus(status, snapshot));

    boolean snapshotHasEnabled = snapshot.containsKey("enabled");
    boolean workerEnabled = isTruthy(snapshot.get("enabled"));
    if (snapshotHasEnabled) {
      assertTrue(workerEnabled,
          () -> "Worker snapshot reported disabled for role " + displayRole + ": " + describeStatus(status, snapshot));
    } else {
      Map<String, Object> processed = snapshotProcessed(snapshot);
      assertFalse(processed.isEmpty(),
          () -> "Worker snapshot missing enabled flag and processed counters for role " + displayRole + ": "
              + describeStatus(status, snapshot));
      assertTrue(hasPositiveCounter(processed),
          () -> "Processed counters show no activity for role " + displayRole + ": " + describeStatus(status, snapshot));
    }

    String state = status.state();
    if (state == null || state.isBlank()) {
      LOGGER.info("Status for role {} instance {} omitted state; relying on snapshot", displayRole, instance);
    } else {
      assertTrue("running".equalsIgnoreCase(state),
          () -> "Expected role " + displayRole + " to report state=Running but was " + describeStatus(status, snapshot));
    }

    StatusEvent.Totals totals = status.totals();
    if (totals == null) {
      LOGGER.info("Status for role {} instance {} omitted totals; relying on snapshot", displayRole, instance);
    } else {
      int desired = totals.desired();
      int running = totals.running();
      int healthy = totals.healthy();
      assertTrue(desired > 0, () -> "Status for role " + displayRole + " reported zero desired workers: "
          + describeStatus(status, snapshot));
      assertTrue(running >= desired,
          () -> "Status for role " + displayRole + " reported running=" + running + " desired=" + desired
              + " details=" + describeStatus(status, snapshot));
      assertTrue(healthy >= desired,
          () -> "Status for role " + displayRole + " reported healthy=" + healthy + " desired=" + desired
              + " details=" + describeStatus(status, snapshot));
    }

    String recordedInstance = workerInstances.get(role);
    assertNotNull(recordedInstance,
        () -> "Worker instance for role " + displayRole + " not recorded despite status=" + describeStatus(status, snapshot));
    assertEquals(recordedInstance, instance,
        () -> "Worker instance mismatch for role " + displayRole + " expected=" + recordedInstance
            + " actual=" + instance);
  }

  private String describeStatus(StatusEvent status, Map<String, Object> snapshot) {
    if (status == null) {
      return "<null status>";
    }
    Object workerEnabledValue = snapshot == null ? null : snapshot.get("enabled");
    String workerEnabled = snapshot == null
        ? "<no snapshot>"
        : snapshot.containsKey("enabled") ? String.valueOf(workerEnabledValue) : "<missing>";
    Map<String, Object> processed = snapshotProcessed(snapshot);
    return "state=" + status.state()
        + ", aggregateEnabled=" + status.enabled()
        + ", workerEnabled=" + workerEnabled
        + ", processed=" + describeProcessed(processed)
        + ", totals=" + describeTotals(status.totals())
        + ", instance=" + status.instance();
  }

  private String describeTotals(StatusEvent.Totals totals) {
    if (totals == null) {
      return "<null totals>";
    }
    return "{desired=" + totals.desired()
        + ", running=" + totals.running()
        + ", healthy=" + totals.healthy()
        + ", enabled=" + totals.enabled() + "}";
  }

  private String describeProcessed(Map<String, Object> processed) {
    if (processed == null || processed.isEmpty()) {
      return "<none>";
    }
    return processed.toString();
  }

  private Map<String, Object> workerSnapshot(StatusEvent status, String role) {
    if (status == null) {
      return Map.of();
    }
    Map<String, Object> data = status.data();
    if (data == null || data.isEmpty()) {
      return Map.of();
    }
    Object workers = data.get("workers");
    if (!(workers instanceof List<?> list)) {
      return Map.of();
    }
    for (Object candidate : list) {
      if (candidate instanceof Map<?, ?> map) {
        Object candidateRole = map.get("role");
        if (candidateRole != null && roleMatches(role, candidateRole.toString())) {
          return copyMap(map);
        }
      }
    }
    return Map.of();
  }

  private Map<String, Object> snapshotConfig(Map<String, Object> snapshot) {
    if (snapshot == null) {
      return Map.of();
    }
    Object config = snapshot.get("config");
    if (config instanceof Map<?, ?> map) {
      return copyMap(map);
    }
    return Map.of();
  }

  private Map<String, Object> snapshotProcessed(Map<String, Object> snapshot) {
    if (snapshot == null) {
      return Map.of();
    }
    Object processed = snapshot.get("processed");
    if (processed instanceof Map<?, ?> map) {
      return copyMap(map);
    }
    return Map.of();
  }

  private Map<String, Object> copyMap(Map<?, ?> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    source.forEach((key, value) -> {
      if (key != null) {
        copy.put(key.toString(), value);
      }
    });
    return copy.isEmpty() ? Map.of() : Map.copyOf(copy);
  }

  private boolean isTruthy(Object value) {
    if (value instanceof Boolean bool) {
      return Boolean.TRUE.equals(bool);
    }
    if (value instanceof Number number) {
      return number.intValue() != 0;
    }
    if (value instanceof String text) {
      return Boolean.parseBoolean(text);
    }
    return false;
  }

  private boolean hasPositiveCounter(Map<String, Object> counters) {
    if (counters == null || counters.isEmpty()) {
      return false;
    }
    for (Object value : counters.values()) {
      if (isPositive(value)) {
        return true;
      }
    }
    return false;
  }

  private boolean isPositive(Object value) {
    if (value instanceof Number number) {
      return number.doubleValue() > 0;
    }
    if (value instanceof String text) {
      try {
        return Double.parseDouble(text) > 0;
      } catch (NumberFormatException ex) {
        return false;
      }
    }
    if (value instanceof Map<?, ?> map) {
      for (Object nested : map.values()) {
        if (isPositive(nested)) {
          return true;
        }
      }
      return false;
    }
    if (value instanceof Iterable<?> iterable) {
      for (Object element : iterable) {
        if (isPositive(element)) {
          return true;
        }
      }
      return false;
    }
    return false;
  }

  private String finalQueueName() {
    String suffix = finalQueueSuffix();
    return queueNameForSuffix(suffix);
  }

  private String finalQueueSuffix() {
    ensureTemplate();
    if (template == null || template.bees() == null || template.bees().isEmpty()) {
      throw new AssertionError("Scenario template has no bees; cannot resolve final queue");
    }
    Bee lastWithInput = null;
    for (Bee bee : template.bees()) {
      if (bee == null || bee.work() == null) {
        continue;
      }
      String inbound = trimmed(bee.work().in());
      if (inbound != null) {
        lastWithInput = bee;
      }
    }
    if (lastWithInput != null && lastWithInput.work() != null) {
      String inbound = trimmed(lastWithInput.work().in());
      LOGGER.info("Final queue resolved from last bee role={} work.in='{}'",
          lastWithInput.role(), inbound);
      return inbound;
    }
    throw new AssertionError("No bees with work.in found in scenario; cannot resolve final queue");
  }

  private String hiveExchangeName() {
    return "ph." + swarmId + ".hive";
  }

  private void ensureFinalQueueTap() {
    if (workQueueConsumer != null) {
      return;
    }
    String exchange = hiveExchangeName();
    String routingKey = finalQueueName();
    workQueueConsumer = WorkQueueConsumer.forExchangeTap(rabbitSubscriptions.connectionFactory(), exchange, routingKey);
    tapQueueName = workQueueConsumer.queueName();
    LOGGER.info("Subscribed to final exchange tap queue={} exchange={} routingKey={}", tapQueueName, exchange, routingKey);
  }

  private boolean looksLikeJson(String text) {
    if (text == null) {
      return false;
    }
    String trimmed = text.trim();
    return !trimmed.isEmpty() && (trimmed.charAt(0) == '{' || trimmed.charAt(0) == '[');
  }

  private void inspectObservabilityTrace(WorkQueueConsumer.Message message) throws IOException {
    Map<String, Object> headers = message.headers();
    Object traceHeader = headers.get("x-ph-trace");
    if (traceHeader == null) {
      LOGGER.info("Final queue message carried no x-ph-trace header");
      return;
    }
    String headerText = traceHeader.toString();
    if (headerText == null || headerText.isBlank()) {
      return;
    }
    JsonNode trace = objectMapper.readTree(headerText);
    JsonNode hopsNode = trace.path("hops");
    if (!hopsNode.isArray()) {
      return;
    }
    List<String> services = new ArrayList<>();
    hopsNode.forEach(node -> {
      String service = node.path("service").asText();
      if (service != null && !service.isBlank()) {
        services.add(service.toLowerCase(Locale.ROOT));
      }
    });
    if (services.isEmpty()) {
      return;
    }
    List<String> expectedPrefix = List.of(GENERATOR_ROLE, MODERATOR_ROLE, PROCESSOR_ROLE);
    assertTrue(containsChain(services, expectedPrefix),
        () -> "Observability hops missing generator→moderator→processor chain: " + services);
    List<String> fullChain = List.of(GENERATOR_ROLE, MODERATOR_ROLE, PROCESSOR_ROLE, POSTPROCESSOR_ROLE);
    if (!containsChain(services, fullChain)) {
      LOGGER.info("Postprocessor hop not yet observed in trace: {}", services);
    }
  }

  private boolean containsChain(List<String> services, List<String> expected) {
    if (expected.isEmpty()) {
      return true;
    }
    int position = -1;
    for (String role : expected) {
      position = findNextIndex(services, role, position + 1);
      if (position < 0) {
        return false;
      }
    }
    return true;
  }

  private int findNextIndex(List<String> services, String role, int startIndex) {
    for (int i = startIndex; i < services.size(); i++) {
      if (roleMatches(role, services.get(i))) {
        return i;
      }
    }
    return -1;
  }

  private List<String> workerRoles() {
    ensureTemplate();
    return List.copyOf(roleAliasMap.keySet());
  }

  private void assertWorkerTopology(String role) {
    String displayRole = actualRoleName(role);
    StatusEvent status = workerStatusByRole.get(role);
    assertNotNull(status, () -> "No status recorded for role " + displayRole);
    String instance = workerInstances.get(role);
    assertNotNull(instance, () -> "No instance recorded for role " + displayRole);

    ControlPlaneTopologyDescriptor descriptor = workerDescriptor(role);
    ControlQueueDescriptor controlQueueDescriptor = descriptor.controlQueue(instance)
        .orElseThrow(() -> new AssertionError("No control queue descriptor for role " + role));

    StatusEvent.QueueEndpoints workQueues = status.queues().work();
    List<String> actualWorkIn = workQueues == null ? List.of() : workQueues.in();
    List<String> actualWorkOut = workQueues == null ? List.of() : workQueues.out();
    assertListEquals("queues.work.in for role " + displayRole, expectedWorkIn(role), actualWorkIn);
    assertListEquals("queues.work.out for role " + displayRole, expectedWorkOut(role), actualWorkOut);

    StatusEvent.QueueEndpoints controlQueues = status.queues().control();
    assertNotNull(controlQueues, () -> "Expected control queue metadata for role " + role);
    String expectedControlQueue = resolveTopologyValue(controlQueueDescriptor.name());
    assertListEquals("queues.control.in for role " + role,
        queueList(expectedControlQueue), controlQueues.in());

    List<String> actualControlRoutes = controlQueues.routes();
    List<String> expectedRoutes = expectedControlRoutes(descriptor, controlQueueDescriptor, instance);
    assertControlRoutes(role, expectedRoutes, actualControlRoutes);

    assertRabbitBindings(role, controlQueueDescriptor, expectedControlQueue);
  }

  private void assertRabbitBindings(String role, ControlQueueDescriptor controlQueueDescriptor, String queueName) {
    assertNotNull(rabbitManagementClient, "RabbitMQ management client not initialised");

    java.util.LinkedHashSet<String> expectedRoutingKeys = controlQueueDescriptor.allBindings().stream()
        .map(this::resolveTopologyValue)
        .filter(value -> value != null && !value.isBlank())
        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

    assertFalse(expectedRoutingKeys.isEmpty(),
        () -> "No expected bindings derived for role " + role + " queue=" + queueName);
    expectedRoutingKeys.forEach(value -> assertNoDefault("expected binding", role, queueName, value));
    assertNoDefault("control queue", role, queueName, queueName);

    List<RabbitManagementClient.QueueBinding> actualBindings = rabbitManagementClient.listBindings(
        endpoints.rabbitMq().virtualHost(), queueName);

    assertFalse(actualBindings.isEmpty(),
        () -> "Management API reported no bindings for queue " + queueName + " role=" + role);

    List<RabbitManagementClient.QueueBinding> filteredBindings = actualBindings.stream()
        .filter(binding -> binding.source() != null && !binding.source().isBlank())
        .toList();

    assertFalse(filteredBindings.isEmpty(),
        () -> "No non-default bindings reported for queue " + queueName + " role=" + role
            + " details=" + actualBindings.stream()
                .map(RabbitManagementClient.QueueBinding::toSummary)
                .toList());

    java.util.LinkedHashSet<String> actualRoutingKeys = new java.util.LinkedHashSet<>();
    for (RabbitManagementClient.QueueBinding binding : filteredBindings) {
      assertNoDefault("binding", role, queueName, binding.routingKey());
      assertNoDefault("binding source", role, queueName, binding.source());
      assertNoDefault("binding destination", role, queueName, binding.destination());
      actualRoutingKeys.add(binding.routingKey());
    }

    assertEquals(expectedRoutingKeys.size(), actualRoutingKeys.size(),
        () -> "Binding count mismatch for role " + role + " queue=" + queueName
            + " expected=" + expectedRoutingKeys.size() + " actual=" + actualRoutingKeys.size()
            + " details=" + filteredBindings.stream()
                .map(RabbitManagementClient.QueueBinding::toSummary)
                .toList());

    assertEquals(expectedRoutingKeys, actualRoutingKeys,
        () -> "Routing key mismatch for role " + role + " queue=" + queueName
            + " expected=" + expectedRoutingKeys + " actual=" + actualRoutingKeys);
  }

  private void assertNoDefault(String context, String role, String queueName, String value) {
    if (value == null) {
      return;
    }
    if (value.toLowerCase(Locale.ROOT).contains("default")) {
      throw new AssertionError(context + " contains forbidden keyword 'default' for role " + role
          + " queue=" + queueName + " value=" + value);
    }
  }

  private boolean isStatusFullForRole(StatusEvent status, String role) {
    return status != null
        && "status-full".equalsIgnoreCase(status.kind())
        && roleMatches(role, status.role());
  }

  private String expectedInboundQueue(String role) {
    Bee bee = findBee(role);
    Work work = bee.work();
    if (work == null || work.in() == null || work.in().isBlank()) {
      return null;
    }
    return queueNameForSuffix(work.in());
  }

  private List<String> expectedWorkIn(String role) {
    String queue = expectedInboundQueue(role);
    return queueList(queue);
  }

  private List<String> expectedWorkOut(String role) {
    Bee bee = findBee(role);
    Work work = bee.work();
    if (work == null || work.out() == null || work.out().isBlank()) {
      return List.of();
    }
    return queueList(queueNameForSuffix(work.out()));
  }

  private List<String> expectedControlRoutes(ControlPlaneTopologyDescriptor descriptor,
      ControlQueueDescriptor controlQueueDescriptor, String instance) {
    LinkedHashSet<String> routes = new LinkedHashSet<>();
    addTopologyValues(routes, controlQueueDescriptor.allBindings());

    ControlPlaneRouteCatalog catalog = descriptor.routes();
    addTopologyValues(routes, expandRoutes(catalog.configSignals(), instance));
    addTopologyValues(routes, expandRoutes(catalog.statusSignals(), instance));
    addTopologyValues(routes, expandRoutes(catalog.lifecycleSignals(), instance));
    addTopologyValues(routes, expandRoutes(catalog.statusEvents(), instance));
    addTopologyValues(routes, expandRoutes(catalog.lifecycleEvents(), instance));
    addTopologyValues(routes, expandRoutes(catalog.otherEvents(), instance));

    return List.copyOf(routes);
  }

  private List<String> expandRoutes(Set<String> templates, String instance) {
    if (templates == null || templates.isEmpty()) {
      return List.of();
    }
    List<String> resolved = new ArrayList<>(templates.size());
    for (String template : templates) {
      if (template == null || template.isBlank()) {
        continue;
      }
      String materialised = template.replace(ControlPlaneRouteCatalog.INSTANCE_TOKEN, instance);
      resolved.add(resolveTopologyValue(materialised));
    }
    return resolved;
  }

  private void addTopologyValues(Set<String> routes, Collection<String> values) {
    if (values == null || values.isEmpty()) {
      return;
    }
    for (String value : values) {
      String resolved = resolveTopologyValue(value);
      if (resolved != null && !resolved.isBlank()) {
        routes.add(resolved);
      }
    }
  }

  private String resolveTopologyValue(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    return value;
  }

  private ControlPlaneTopologyDescriptor workerDescriptor(String role) {
    try {
      String actualRole = actualRoleName(role);
      ControlPlaneTopologySettings settings = new ControlPlaneTopologySettings(
          swarmId, controlPlane.controlQueuePrefix(), Map.of());
      return ControlPlaneTopologyDescriptorFactory.forWorkerRole(actualRole, settings);
    } catch (IllegalArgumentException ex) {
      throw new AssertionError("Unsupported worker role " + role, ex);
    }
  }

  private Bee findBee(String role) {
    ensureTemplate();
    return template.bees().stream()
        .filter(bee -> bee != null && bee.role() != null && roleMatches(role, bee.role()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No bee with role " + role + " in template"));
  }

  private Bee findBeeOptional(String role) {
    if (template == null || template.bees() == null || role == null || role.isBlank()) {
      return null;
    }
    return template.bees().stream()
        .filter(bee -> bee != null && bee.role() != null && roleMatches(role, bee.role()))
        .findFirst()
        .orElse(null);
  }

  private String queueNameForSuffix(String suffix) {
    if (suffix == null || suffix.isBlank()) {
      return null;
    }
    String trimmed = suffix.trim();
    if (trimmed.contains(".")) {
      return trimmed;
    }
    return "ph." + swarmId + "." + trimmed;
  }

  private String trimmed(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private List<String> queueList(String queue) {
    return queue == null ? List.of() : List.of(queue);
  }

  private void assertListEquals(String context, List<String> expected, List<String> actual) {
    List<String> expectedList = expected == null ? List.of() : List.copyOf(expected);
    List<String> actualList = actual == null ? List.of() : List.copyOf(actual);
    assertEquals(expectedList, actualList,
        () -> context + " mismatch expected=" + expectedList + " actual=" + actualList);
  }

  private void assertControlRoutes(String role, List<String> expected, List<String> actual) {
    List<String> expectedSorted = new ArrayList<>(expected == null ? List.of() : expected);
    List<String> actualSorted = new ArrayList<>(actual == null ? List.of() : actual);
    expectedSorted.sort(String::compareTo);
    actualSorted.sort(String::compareTo);
    assertEquals(expectedSorted, actualSorted,
        () -> "control.routes mismatch for role " + role + " expected=" + expectedSorted + " actual=" + actualSorted);
  }

  private void ensureHarness() {
    Assumptions.assumeTrue(endpoints != null, "Harness not initialised");
  }

  private void ensureTemplate() {
    ensureHarness();
    Assumptions.assumeTrue(template != null, "Scenario template not loaded");
    if (!roleAliasesInitialised) {
      rebuildRoleAliases();
    }
  }

  private void resetRoleAliases() {
    roleAliasMap.clear();
    roleAliasesInitialised = false;
  }

  private void rebuildRoleAliases() {
    roleAliasMap.clear();
    if (template != null && template.bees() != null) {
      for (Bee bee : template.bees()) {
        if (bee == null || bee.role() == null) {
          continue;
        }
        String actual = bee.role().trim();
        if (actual.isEmpty()) {
          continue;
        }
        String normalized = normalizeRole(actual);
        if (normalized != null && !normalized.isEmpty()) {
          roleAliasMap.putIfAbsent(normalized, actual);
        }
      }
    }
    roleAliasesInitialised = true;
  }

  private String normalizeRole(String role) {
    if (role == null) {
      return null;
    }
    String trimmed = role.trim();
    return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
  }

  private String roleKey(String alias) {
    ensureTemplate();
    String normalizedAlias = normalizeRole(alias);
    if (normalizedAlias == null) {
      return null;
    }
    if (roleAliasMap.containsKey(normalizedAlias)) {
      return normalizedAlias;
    }
    return roleAliasMap.keySet().stream()
        .filter(key -> roleMatches(normalizedAlias, key))
        .findFirst()
        .orElse(normalizedAlias);
  }

  private String actualRoleName(String alias) {
    String key = roleKey(alias);
    if (key == null) {
      return alias;
    }
    return roleAliasMap.getOrDefault(key, alias == null ? null : alias.trim());
  }

  private void logScenarioTemplate(String context) {
    if (scenarioDetails == null || scenarioDetails.template() == null) {
      LOGGER.warn("Scenario template not available for logging (context={})", context);
      return;
    }
    try {
      String json = objectMapper.writeValueAsString(scenarioDetails.template());
      LOGGER.info("Scenario template (context={} id={}): {}", context, scenarioDetails.id(), json);
    } catch (Exception ex) {
      LOGGER.warn("Failed to serialise scenario template (context={} id={}): {}", context, scenarioDetails.id(), ex.toString());
    }
  }

  private boolean roleMatches(String expectedAlias, String actualRole) {
    String expected = normalizeRole(expectedAlias);
    String actual = normalizeRole(actualRole);
    if (expected == null || actual == null) {
      return false;
    }
    return expected.equals(actual) || actual.contains(expected);
  }

  private void ensureCreateResponse() {
    Assumptions.assumeTrue(createResponse != null, "Create request was not issued");
  }

  private void ensureStartResponse() {
    ensureCreateResponse();
    Assumptions.assumeTrue(startResponse != null, "Start request was not issued");
  }

  private void ensureStopResponse() {
    ensureStartResponse();
    Assumptions.assumeTrue(stopResponse != null, "Stop request was not issued");
  }

  private void ensureRemoveResponse() {
    ensureStopResponse();
    Assumptions.assumeTrue(removeResponse != null, "Remove request was not issued");
  }

  private void awaitReady(String signal, ControlResponse response) {
    String correlationId = response.correlationId();
    SwarmAssertions.await(signal + " confirmation", () -> {
      Optional<ReadyConfirmation> ready = controlPlaneEvents.readyConfirmation(signal, correlationId);
      assertTrue(ready.isPresent(), () -> "Missing ready confirmation for " + signal + " correlation=" + correlationId);
    });
  }

  private void assertNoErrors(String correlationId, String context) {
    List<ErrorConfirmation> errors = controlPlaneEvents.errorsForCorrelation(correlationId);
    assertTrue(errors.isEmpty(), () -> "Unexpected error confirmations for " + context + ": " + errors);
  }

  private void assertWatchMatched(ControlResponse response) {
    String signal = signalFromWatch(response);
    if (signal.isEmpty()) {
      return;
    }
    controlPlaneEvents.findReady(signal, response.correlationId())
        .ifPresent(env -> {
          String expected = response.watch().successTopic();
          if (expected != null && !expected.isBlank()) {
            assertEquals(expected, env.routingKey(), "Watch success topic should match emitted event");
          }
        });
    String errorTopic = response.watch().errorTopic();
    if (errorTopic != null && !errorTopic.isBlank()) {
      assertFalse(controlPlaneEvents.hasEventOnRoutingKey(errorTopic),
          () -> "Unexpected error event detected on " + errorTopic);
    }
  }

  private String signalFromWatch(ControlResponse response) {
    // success topic format: ev.ready.<signal>.<swarm>... -> extract <signal>
    String topic = response.watch().successTopic();
    if (topic == null || topic.isBlank()) {
      return "";
    }
    String[] parts = topic.split("\\.");
    if (parts.length < 3) {
      return "";
    }
    String raw = parts[2];
    return raw.toLowerCase(Locale.ROOT);
  }

  private Set<String> expectedQueueSuffixes(SwarmTemplate template) {
    Set<String> suffixes = new LinkedHashSet<>();
    if (template.bees() != null) {
      for (Bee bee : template.bees()) {
        Work work = bee.work();
        if (work != null) {
          if (work.in() != null && !work.in().isBlank()) {
            suffixes.add(work.in());
          }
          if (work.out() != null && !work.out().isBlank()) {
            suffixes.add(work.out());
          }
        }
      }
    }
    return suffixes;
  }

  private String idKey(String action) {
    return idempotencyPrefix + "-" + action + "-" + UUID.randomUUID();
  }
}
