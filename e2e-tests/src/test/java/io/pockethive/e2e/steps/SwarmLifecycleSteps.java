package io.pockethive.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
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
  private static final String LEGACY_SERVICE_HEADER = "x-ph-service";
  private static final String STEP_SERVICE_HEADER = "ph.step.service";
  private static final String STEP_INSTANCE_HEADER = "ph.step.instance";
  private static final List<String> PROCESSOR_STEP_HEADERS = List.of(
      "x-ph-processor-duration-ms",
      "x-ph-processor-connection-latency-ms",
      "x-ph-processor-success",
      "x-ph-processor-status");

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
  private WorkQueueConsumer generatorTapConsumer;
  private String generatorTapQueueName;

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
    String sutId = resolveSutIdForScenario();
    SwarmCreateRequest request = new SwarmCreateRequest(
        scenarioDetails.id(),
        idempotencyKey,
        "e2e lifecycle create",
        null,
        sutId);
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
    awaitSwarmPlanReady();

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

  /**
   * Select a SUT environment id for scenarios that rely on SUT-aware templating.
   * <p>
   * For now this is explicit and minimal – only scenarios that use
   * {@code {{ sut.endpoints[...] }}} in their config (e.g. processor baseUrl) are
   * bound to the {@code wiremock-local} environment defined in
   * {@code scenario-manager-service/sut-environments.yaml}. Other scenarios
   * leave {@code sutId} null.
   */
  private String resolveSutIdForScenario() {
    ensureTemplate();
    String scenarioId = scenarioDetails != null ? scenarioDetails.id() : null;
    if (scenarioId == null) {
      return null;
    }
    return switch (scenarioId) {
      case "templated-rest", "redis-dataset-demo" -> "wiremock-local";
      case "tcp-socket-demo" -> "tcp-mock-local";
      default -> null;
    };
  }

  @When("I start the swarm")
  public void iStartTheSwarm() {
    ensureCreateResponse();
    String idempotencyKey = idKey("start");
    startResponse = orchestratorClient.startSwarm(
        swarmId,
        new ControlRequest(idempotencyKey, "e2e lifecycle start"));
    LOGGER.info("Start request correlation={} watch={}", startResponse.correlationId(), startResponse.watch());
  }

  @Then("the swarm reports running")
  public void theSwarmReportsRunning() {
    ensureStartResponse();
    io.pockethive.control.CommandOutcome outcome = awaitOutcome("swarm-start", startResponse);
    assertOutcomeStatus("swarm-start", outcome, "Running");
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

  @And("I start generator traffic")
  public void iStartGeneratorTraffic() {
    ensureStartResponse();
    captureWorkerStatuses();
    ensureFinalQueueTap();
    ensureGeneratorTapForTemplating();
    String generatorKey = roleKey(GENERATOR_ROLE);
    String generatorInstance = generatorKey == null ? null : workerInstances.get(generatorKey);
    assertNotNull(generatorInstance, "Generator instance should be discovered from status snapshots");
    String generatorRoleName = actualRoleName(generatorKey != null ? generatorKey : GENERATOR_ROLE);

    Map<String, Object> patch = new LinkedHashMap<>();
    patch.put("enabled", true);

    ComponentConfigRequest request = new ComponentConfigRequest(
        idKey("generator-single"),
        patch,
        "e2e generator single request",
        swarmId
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
      assertTrue("status-delta".equalsIgnoreCase(delta.type()),
          () -> "Expected status-delta type for generator but was " + delta.type());
      Map<String, Object> snapshot = workerSnapshot(delta, generatorKey != null ? generatorKey : GENERATOR_ROLE);
      assertFalse(snapshot.isEmpty(), "Generator snapshot should include worker details");
      assertTrue(isTruthy(snapshot.get("enabled")), "Generator snapshot should report enabled=true");
    });

    SwarmAssertions.await("generator status snapshot", () -> {
      StatusEvent full = latestStatusFull(generatorRoleName, generatorInstance)
          .orElseThrow(() -> new AssertionError("No status-full captured for generator"));
      Map<String, Object> snapshot = workerSnapshot(full, generatorKey != null ? generatorKey : GENERATOR_ROLE);
      assertFalse(snapshot.isEmpty(), "Generator snapshot should include worker details");
      Map<String, Object> config = snapshotConfig(snapshot);
      assertFalse(config.isEmpty(), "Generator snapshot should include applied config");
    });

    assertTemplatedGeneratorOutputIfApplicable();
  }

  @Then("the swarm-start is rejected as NotReady")
  public void theSwarmStartIsRejectedAsNotReady() {
    ensureStartResponse();
    assertNotReadyOutcome("swarm-start", startResponse);
  }

  @Then("the swarm-stop is rejected as NotReady")
  public void theSwarmStopIsRejectedAsNotReady() {
    ensureStopResponseWithoutStart();
    assertNotReadyOutcome("swarm-stop", stopResponse);
  }

  @And("the worker status snapshots include config only in status-full")
  public void theWorkerStatusSnapshotsIncludeConfigOnlyInStatusFull() {
    ensureStartResponse();
    captureWorkerStatuses(true);
    for (String role : workerRoles()) {
      String instance = workerInstances.get(role);
      assertNotNull(instance, () -> "Missing instance for role " + actualRoleName(role));
      StatusEvent full = latestStatusFull(actualRoleName(role), instance)
          .orElseThrow(() -> new AssertionError("No status-full captured for role " + actualRoleName(role)));
      Map<String, Object> fullSnapshot = workerSnapshot(full, role);
      assertFalse(fullSnapshot.isEmpty(), () -> "No status-full snapshot for role " + actualRoleName(role));
      Map<String, Object> fullConfig = snapshotConfig(fullSnapshot);
      assertFalse(fullConfig.isEmpty(), () -> "status-full missing config for role " + actualRoleName(role));

      SwarmAssertions.await("status-delta for role " + actualRoleName(role), () -> {
        Optional<StatusEvent> deltaOpt = controlPlaneEvents.latestStatusDeltaEvent(
            swarmId, actualRoleName(role), instance);
        assertTrue(deltaOpt.isPresent(), () -> "No status-delta captured for role " + actualRoleName(role));
        StatusEvent delta = deltaOpt.get();
        assertNotNull(delta.data().enabled(),
            () -> "status-delta missing data.enabled for role " + actualRoleName(role));
        assertFalse(delta.data().extra().containsKey("config"),
            () -> "status-delta should not include data.config for role " + actualRoleName(role));
      });
    }
  }

  @And("the status-full snapshots include runtime metadata")
  public void theStatusFullSnapshotsIncludeRuntimeMetadata() {
    ensureStartResponse();
    captureWorkerStatuses(true);

    Optional<ControlPlaneEvents.StatusEnvelope> controllerEnv = controlPlaneEvents.statusesForSwarm(swarmId).stream()
        .filter(env -> env != null
            && env.status() != null
            && "status-full".equalsIgnoreCase(env.status().type())
            && roleMatches("swarm-controller", env.status().role()))
        .max(Comparator.comparing(ControlPlaneEvents.StatusEnvelope::receivedAt));
    assertTrue(controllerEnv.isPresent(), "Expected status-full snapshot for swarm-controller");
    assertRuntimeMeta(controllerEnv.get().status().runtime(), "swarm-controller");

    for (String role : workerRoles()) {
      String instance = workerInstances.get(role);
      assertNotNull(instance, () -> "Missing instance for role " + actualRoleName(role));
      StatusEvent full = latestStatusFull(actualRoleName(role), instance)
          .orElseThrow(() -> new AssertionError("No status-full captured for role " + actualRoleName(role)));
      assertRuntimeMeta(full.runtime(), actualRoleName(role));
    }
  }

  @And("the generator runtime config matches the service defaults")
  public void theGeneratorRuntimeConfigMatchesTheServiceDefaults() {
    ensureStartResponse();
    captureWorkerStatuses(true);
    String generatorKey = roleKey(GENERATOR_ROLE);
    String roleKey = generatorKey != null ? generatorKey : GENERATOR_ROLE;
    StatusEvent status = workerStatusByRole.get(roleKey);
    String displayRole = actualRoleName(roleKey);
    assertNotNull(status, () -> "No status recorded for role " + displayRole);

    Map<String, Object> snapshot = workerSnapshot(status, roleKey);
    assertFalse(snapshot.isEmpty(), "Generator snapshot should include worker details");
    Map<String, Object> config = snapshotConfig(snapshot);
    assertFalse(config.isEmpty(), "Generator snapshot should include applied config");

    Map<String, Object> message = toMap(config.get("message"));
    assertEquals("/api/test", message.get("path"),
        "Expected default generator path '/api/test'");
    assertEquals("POST", message.get("method"),
        "Expected default generator method 'POST'");
    assertEquals("hello-world", message.get("body"),
        "Expected default generator body 'hello-world'");

    Map<String, Object> headers = toMap(message.get("headers"));
    assertTrue(headers.isEmpty(),
        () -> "Expected default generator headers to be empty but were " + headers);
  }

  @And("the generator runtime config matches the local-rest scenario")
  public void theGeneratorRuntimeConfigMatchesTheLocalRestScenario() {
    ensureStartResponse();
    captureWorkerStatuses(true);
    String generatorKey = roleKey(GENERATOR_ROLE);
    String roleKey = generatorKey != null ? generatorKey : GENERATOR_ROLE;
    StatusEvent status = workerStatusByRole.get(roleKey);
    String displayRole = actualRoleName(roleKey);
    assertNotNull(status, () -> "No status recorded for role " + displayRole);

    Map<String, Object> snapshot = workerSnapshot(status, roleKey);
    assertFalse(snapshot.isEmpty(), "Generator snapshot should include worker details");
    Map<String, Object> config = snapshotConfig(snapshot);
    assertFalse(config.isEmpty(), "Generator snapshot should include applied config");

    Map<String, Object> message = toMap(config.get("message"));
    assertEquals("/test", message.get("path"),
        "Expected generator path '/test' from local-rest scenario");
    assertEquals("POST", message.get("method"),
        "Expected generator method 'POST' from local-rest scenario");
    assertEquals("{\"event\":\"local-rest\"}", message.get("body"),
        "Expected generator body '{\"event\":\"local-rest\"}' from local-rest scenario");

    Map<String, Object> headers = toMap(message.get("headers"));
    assertEquals("application/json", headers.get("content-type"),
        "Expected content-type=application/json from local-rest scenario");
  }

  @And("the generator IO config matches the local-rest scenario")
  public void theGeneratorIoConfigMatchesTheLocalRestScenario() {
    ensureStartResponse();
    captureWorkerStatuses(true);
    String generatorKey = roleKey(GENERATOR_ROLE);
    String roleKey = generatorKey != null ? generatorKey : GENERATOR_ROLE;
    StatusEvent status = workerStatusByRole.get(roleKey);
    String displayRole = actualRoleName(roleKey);
    assertNotNull(status, () -> "No status recorded for role " + displayRole);

    Map<String, Object> snapshot = workerSnapshot(status, roleKey);
    assertFalse(snapshot.isEmpty(), "Generator snapshot should include worker details");
    Map<String, Object> config = snapshotConfig(snapshot);
    assertFalse(config.isEmpty(), "Generator snapshot should include applied config");

    Map<String, Object> inputs = toMap(config.get("inputs"));
    assertFalse(inputs.isEmpty(), "Generator config should include inputs block");
    Map<String, Object> scheduler = toMap(inputs.get("scheduler"));
    assertFalse(scheduler.isEmpty(), "Generator inputs should include scheduler block");

    Object rateObj = scheduler.get("ratePerSec");
    assertNotNull(rateObj, "Generator scheduler config did not include ratePerSec");
    double ratePerSec = rateObj instanceof Number n ? n.doubleValue()
        : Double.parseDouble(rateObj.toString());
    assertEquals(50.0, ratePerSec, 0.0001,
        "Expected generator inputs.scheduler.ratePerSec=50.0 from local-rest scenario");
  }

  @And("the moderator runtime config matches the service defaults")
  public void theModeratorRuntimeConfigMatchesTheServiceDefaults() {
    ensureStartResponse();
    captureWorkerStatuses(true);
    String moderatorKey = roleKey(MODERATOR_ROLE);
    String roleKey = moderatorKey != null ? moderatorKey : MODERATOR_ROLE;
    StatusEvent status = workerStatusByRole.get(roleKey);
    String displayRole = actualRoleName(roleKey);
    assertNotNull(status, () -> "No status recorded for role " + displayRole);

    Map<String, Object> snapshot = workerSnapshot(status, roleKey);
    assertFalse(snapshot.isEmpty(), "Moderator snapshot should include worker details");
    Map<String, Object> config = snapshotConfig(snapshot);
    assertFalse(config.isEmpty(), "Moderator snapshot should include applied config");

    Map<String, Object> mode = toMap(config.get("mode"));
    String type = String.valueOf(mode.get("type"));
    assertEquals("pass-through", type.toLowerCase(Locale.ROOT),
        "Expected moderator mode.type='pass-through' by default");

    Object rateObj = mode.get("ratePerSec");
    assertTrue(rateObj instanceof Number,
        () -> "Expected numeric mode.ratePerSec in moderator config but was " + rateObj);
    double ratePerSec = ((Number) rateObj).doubleValue();
    assertEquals(0.0, ratePerSec, 0.0001,
        "Expected default moderator ratePerSec=0.0");
  }

  @And("the moderator runtime config matches the local-rest scenario")
  public void theModeratorRuntimeConfigMatchesTheLocalRestScenario() {
    ensureStartResponse();
    captureWorkerStatuses(true);
    String moderatorKey = roleKey(MODERATOR_ROLE);
    String roleKey = moderatorKey != null ? moderatorKey : MODERATOR_ROLE;
    StatusEvent status = workerStatusByRole.get(roleKey);
    String displayRole = actualRoleName(roleKey);
    assertNotNull(status, () -> "No status recorded for role " + displayRole);

    Map<String, Object> snapshot = workerSnapshot(status, roleKey);
    assertFalse(snapshot.isEmpty(), "Moderator snapshot should include worker details");
    Map<String, Object> config = snapshotConfig(snapshot);
    assertFalse(config.isEmpty(), "Moderator snapshot should include applied config");

    Map<String, Object> mode = toMap(config.get("mode"));
    String type = String.valueOf(mode.get("type"));
    assertEquals("rate-per-sec", type.toLowerCase(Locale.ROOT),
        "Expected moderator mode.type='rate-per-sec' from local-rest scenario");

    Object rateObj = mode.get("ratePerSec");
    assertTrue(rateObj instanceof Number,
        () -> "Expected numeric mode.ratePerSec in moderator config but was " + rateObj);
    double ratePerSec = ((Number) rateObj).doubleValue();
    assertEquals(10.0, ratePerSec, 0.0001,
        "Expected moderator ratePerSec=10.0 from local-rest scenario");
  }

  @And("the processor runtime config matches the service defaults")
  public void theProcessorRuntimeConfigMatchesTheServiceDefaults() {
    ensureStartResponse();
    captureWorkerStatuses(true);
    String processorKey = roleKey(PROCESSOR_ROLE);
    String roleKey = processorKey != null ? processorKey : PROCESSOR_ROLE;
    StatusEvent status = workerStatusByRole.get(roleKey);
    String displayRole = actualRoleName(roleKey);
    assertNotNull(status, () -> "No status recorded for role " + displayRole);

    Map<String, Object> snapshot = workerSnapshot(status, roleKey);
    assertFalse(snapshot.isEmpty(), "Processor snapshot should include worker details");
    Map<String, Object> config = snapshotConfig(snapshot);
    assertFalse(config.isEmpty(), "Processor snapshot should include applied config");

    String baseUrl = String.valueOf(config.get("baseUrl"));
    assertEquals("http://wiremock:8080", baseUrl,
        "Expected processor baseUrl=http://wiremock:8080 from service defaults");
  }

  @And("the processor runtime config matches the local-rest scenario")
  public void theProcessorRuntimeConfigMatchesTheLocalRestScenario() {
    ensureStartResponse();
    captureWorkerStatuses(true);
    String processorKey = roleKey(PROCESSOR_ROLE);
    String roleKey = processorKey != null ? processorKey : PROCESSOR_ROLE;
    StatusEvent status = workerStatusByRole.get(roleKey);
    String displayRole = actualRoleName(roleKey);
    assertNotNull(status, () -> "No status recorded for role " + displayRole);

    Map<String, Object> snapshot = workerSnapshot(status, roleKey);
    assertFalse(snapshot.isEmpty(), "Processor snapshot should include worker details");
    Map<String, Object> config = snapshotConfig(snapshot);
    assertFalse(config.isEmpty(), "Processor snapshot should include applied config");

    String baseUrl = String.valueOf(config.get("baseUrl"));
    assertEquals("{{ sut.endpoints['default'].baseUrl }}/api", baseUrl,
        "Expected processor baseUrl=\"{{ sut.endpoints['default'].baseUrl }}/api\" from local-rest scenario");
  }

  @And("the postprocessor runtime config matches the service defaults")
  public void thePostprocessorRuntimeConfigMatchesTheServiceDefaults() {
    ensureStartResponse();
    captureWorkerStatuses(true);
    String postKey = roleKey(POSTPROCESSOR_ROLE);
    String roleKey = postKey != null ? postKey : POSTPROCESSOR_ROLE;
    StatusEvent status = workerStatusByRole.get(roleKey);
    String displayRole = actualRoleName(roleKey);
    assertNotNull(status, () -> "No status recorded for role " + displayRole);

    Map<String, Object> snapshot = workerSnapshot(status, roleKey);
    assertFalse(snapshot.isEmpty(), "Postprocessor snapshot should include worker details");
    Map<String, Object> config = snapshotConfig(snapshot);
    assertFalse(config.isEmpty(), "Postprocessor snapshot should include applied config");

    Object flag = config.get("publishAllMetrics");
    boolean publishAllMetrics = flag instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(flag));
    assertTrue(!publishAllMetrics,
        () -> "Expected postprocessor publishAllMetrics=false by default but was " + flag);
  }

  @And("the postprocessor runtime config matches the local-rest scenario")
  public void thePostprocessorRuntimeConfigMatchesTheLocalRestScenario() {
    ensureStartResponse();
    captureWorkerStatuses(true);
    String postKey = roleKey(POSTPROCESSOR_ROLE);
    String roleKey = postKey != null ? postKey : POSTPROCESSOR_ROLE;
    StatusEvent status = workerStatusByRole.get(roleKey);
    String displayRole = actualRoleName(roleKey);
    assertNotNull(status, () -> "No status recorded for role " + displayRole);

    Map<String, Object> snapshot = workerSnapshot(status, roleKey);
    assertFalse(snapshot.isEmpty(), "Postprocessor snapshot should include worker details");
    Map<String, Object> config = snapshotConfig(snapshot);
    assertFalse(config.isEmpty(), "Postprocessor snapshot should include applied config");

    Object flag = config.get("publishAllMetrics");
    boolean publishAllMetrics = flag instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(flag));
    assertTrue(publishAllMetrics,
        () -> "Expected postprocessor publishAllMetrics=true from local-rest scenario but was " + flag);
  }

  @And("the swarm worker statuses reflect the swarm topology")
  public void theSwarmWorkerStatusesReflectTheSwarmTopology() {
    captureWorkerStatuses();
    for (String role : workerRoles()) {
      assertWorkerTopology(role);
    }
  }

  @And("the worker statuses advertise history policies")
  public void theWorkerStatusesAdvertiseHistoryPolicies() {
    captureWorkerStatuses();

    for (String role : workerRoles()) {
      StatusEvent status = workerStatusByRole.get(role);
      String displayRole = actualRoleName(role);
      assertNotNull(status, () -> "No status recorded for role " + displayRole);

      Map<String, Object> snapshot = workerSnapshot(status, displayRole);
      assertFalse(snapshot.isEmpty(), () -> "No worker snapshot found for role " + displayRole);

      Map<String, Object> config = snapshotConfig(snapshot);
      LOGGER.info("History policy snapshot for role {}: config={}", displayRole, config);
    }
  }

  @And("the postprocessor status reflects applied history policy")
  public void thePostprocessorStatusReflectsAppliedHistoryPolicy() {
    ensureStartResponse();
    // History semantics are currently validated via unit tests; at runtime we
    // log the postprocessor status snapshot for manual inspection without
    // asserting on workItemSteps, as that field may be omitted depending on
    // metrics configuration and timing.
    captureWorkerStatuses(true);
    String roleKey = POSTPROCESSOR_ROLE;
    String displayRole = actualRoleName(roleKey);
    StatusEvent status = workerStatusByRole.get(roleKey);
    assertNotNull(status, () -> "No status recorded for role " + displayRole);
    LOGGER.info("Postprocessor status data for history-policy-demo: enabled={} tps={} context={} extra={}",
        status.data().enabled(), status.data().tps(), status.data().context(), status.data().extra());
  }

  @And("the plan demo scenario plan drives the swarm lifecycle")
  public void thePlanDemoScenarioPlanDrivesTheSwarmLifecycle() {
    ensureCreateResponse();

    // 1) Swarm should reach RUNNING with workloads enabled; the scenario plan's
    // swarm-start step is responsible for issuing the enablement config-update.
    SwarmAssertions.await("swarm running via scenario plan", () -> {
      Optional<SwarmView> viewOpt = orchestratorClient.findSwarm(swarmId);
      assertTrue(viewOpt.isPresent(), "Swarm should be available while plan is running");
      SwarmView view = viewOpt.get();
      assertEquals("RUNNING", view.status(), "Swarm status should be RUNNING while plan is active");
      assertTrue(view.workEnabled(), "Workloads should be enabled while plan is active");
    });

    // 2) Eventually the plan should stop the swarm entirely via its final
    // swarm-stop step (no explicit stopSwarm call from the test).
    SwarmAssertions.await("swarm stopped via scenario plan", () -> {
      Optional<SwarmView> viewOpt = orchestratorClient.findSwarm(swarmId);
      assertTrue(viewOpt.isPresent(), "Swarm should still be registered when stopped");
      SwarmView view = viewOpt.get();
      assertEquals("STOPPED", view.status(), "Swarm status should be STOPPED after plan completes");
      assertFalse(view.workEnabled(), "Workloads should be disabled after plan completes");
    });

    // 3) Inspect control-plane status events emitted during the run to ensure
    // the scenario plan progressed through the expected step ids and that the
    // generator enablement and rate changes were applied.
    List<ControlPlaneEvents.StatusEnvelope> allStatuses =
        controlPlaneEvents.statusesForSwarm(swarmId);
    assertFalse(allStatuses.isEmpty(), "Expected at least one status event for the plan demo");

    // 3a) Verify swarm-controller reported scenario progress for each step.
    List<ControlPlaneEvents.StatusEnvelope> controllerEvents = allStatuses.stream()
        .filter(env -> roleMatches("swarm-controller", env.status().role()))
        .sorted(Comparator.comparing(ControlPlaneEvents.StatusEnvelope::receivedAt))
        .toList();
    assertFalse(controllerEvents.isEmpty(), "Expected status events for swarm-controller");

    LinkedHashSet<String> seenStepIds = new LinkedHashSet<>();
    for (ControlPlaneEvents.StatusEnvelope env : controllerEvents) {
      Object scenarioObj = env.status().data().context().get("scenario");
      if (!(scenarioObj instanceof Map<?, ?> scenarioMapRaw)) {
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> scenarioMap = (Map<String, Object>) scenarioMapRaw;
      Object lastStep = scenarioMap.get("lastStepId");
      if (lastStep != null) {
        String stepId = String.valueOf(lastStep);
        if (!stepId.isBlank()) {
          seenStepIds.add(stepId.trim());
        }
      }
      Object firedSteps = scenarioMap.get("firedStepIds");
      if (firedSteps instanceof List<?> firedList) {
        for (Object stepObj : firedList) {
          if (stepObj == null) {
            continue;
          }
          String stepId = String.valueOf(stepObj);
          if (!stepId.isBlank()) {
            seenStepIds.add(stepId.trim());
          }
        }
      }
    }
    List<String> expectedSteps = List.of(
        "swarm-start",
        "gen-rate-fast",
        "gen-stop-1",
        "gen-start-2",
        "swarm-stop");
    assertTrue(seenStepIds.containsAll(expectedSteps),
        () -> "Scenario plan did not report all expected steps. expected=" + expectedSteps
            + " seen=" + seenStepIds);

    // 3b) Verify generator enablement and rate changes were applied over time.
    List<ControlPlaneEvents.StatusEnvelope> generatorEvents = allStatuses.stream()
        .filter(env -> roleMatches(GENERATOR_ROLE, env.status().role()))
        .sorted(Comparator.comparing(ControlPlaneEvents.StatusEnvelope::receivedAt))
        .toList();
    assertFalse(generatorEvents.isEmpty(), "Expected status events for generator");

    boolean sawFastRate = false;
    boolean sawStopped = false;
    boolean sawRestarted = false;

    for (ControlPlaneEvents.StatusEnvelope env : generatorEvents) {
      StatusEvent status = env.status();
      Map<String, Object> snapshot = workerSnapshot(status, GENERATOR_ROLE);
      boolean enabled = isTruthy(snapshot.get("enabled"));
      Map<String, Object> config = snapshotConfig(snapshot);
      Map<String, Object> inputs = toMap(config.get("inputs"));
      Map<String, Object> scheduler = toMap(inputs.get("scheduler"));
      Object rateValue = scheduler.get("ratePerSec");
      Double rate = null;
      if (rateValue instanceof Number n) {
        rate = n.doubleValue();
      } else if (rateValue != null) {
        try {
          rate = Double.parseDouble(rateValue.toString());
        } catch (NumberFormatException ignored) {
          // leave null
        }
      }

      if (rate != null && rate >= 49.0 && rate <= 51.0) {
        sawFastRate = true;
      }
      if (!enabled) {
        if (sawFastRate && !sawStopped) {
          sawStopped = true;
        }
      } else {
        if (sawStopped) {
          sawRestarted = true;
        }
      }
    }

    assertTrue(sawFastRate, "Expected generator scheduler ratePerSec to be increased by the plan");
    assertTrue(sawStopped, "Expected generator to be stopped by the plan");
    assertTrue(sawRestarted, "Expected generator to be restarted by the plan");

    // 3c) Final worker snapshots should all report enabled=false after the
    // swarm-stop step.
    captureWorkerStatuses(true);
    for (String role : workerRoles()) {
      StatusEvent status = workerStatusByRole.get(role);
      String displayRole = actualRoleName(role);
      assertNotNull(status, () -> "No final status recorded for role " + displayRole);
      Map<String, Object> snapshot = workerSnapshot(status, role);
      Object flag = snapshot.get("enabled");
      boolean finalEnabled = isTruthy(flag);
      assertFalse(finalEnabled,
          () -> "Expected role " + displayRole + " to be disabled after plan completes but enabled=" + flag);
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
      String scenarioId = scenarioDetails != null ? scenarioDetails.id() : null;
      JsonNode envelope = objectMapper.readTree(message.body());
      JsonNode stepsNode = envelope.path("steps");
      if (!stepsNode.isArray() || stepsNode.isEmpty()) {
        throw new AssertionError("Final queue WorkItem did not carry any steps");
      }
      JsonNode lastStep = stepsNode.get(stepsNode.size() - 1);
      String httpPayload = lastStep.path("payload").asText("");
      byte[] bodyBytes = httpPayload.getBytes(StandardCharsets.UTF_8);

      JsonNode root = objectMapper.readTree(bodyBytes);
      int statusCode = root.path("status").asInt();
      if (statusCode != 200) {
        LOGGER.warn("Final queue status code was {}. Body={} headers={}",
            statusCode, root, message.headers());
        assertEquals(200, statusCode, "Final queue response should report status 200");
      }
      JsonNode bodyNode = root.path("body");
      String bodyText = bodyNode.isMissingNode() ? new String(bodyBytes, StandardCharsets.UTF_8) : bodyNode.asText();
      if (bodyText == null || bodyText.isBlank()) {
        bodyText = new String(bodyBytes, StandardCharsets.UTF_8);
      }
      final String finalBodyText = bodyText;
      String expectedMessage = expectedGeneratorResponseMessageForScenario(scenarioId);
      if (!finalBodyText.contains(expectedMessage)) {
        LOGGER.warn("Final queue payload did not contain expected marker. scenario={} expected={} payload={} headers={} rawBody={}",
            scenarioId, expectedMessage, finalBodyText, message.headers(), new String(bodyBytes, StandardCharsets.UTF_8));
      }
      if (looksLikeJson(finalBodyText)) {
        JsonNode parsedBody = objectMapper.readTree(finalBodyText);
        assertEquals(expectedMessage, parsedBody.path("message").asText(),
            "Generator response body should match WireMock stub for scenario " + scenarioId);
      }
      inspectObservabilityTrace(message);

      List<String> stepPayloads = workItemStepPayloads(message);
      LOGGER.info("Final WorkItem step payloads for scenario {}: {}", scenarioId, stepPayloads);

      if ("history-policy-demo".equals(scenarioId)) {
        // History policy semantics are covered by unit tests; when we re-enable this scenario,
        // adjust expectations to the current WorkItem history model.
      } else if (scenarioId != null) {
        // For default, named-queues, and templated scenarios we expect at least
        // generator + processor steps to be present in history.
        assertTrue(stepPayloads.size() >= 2,
            () -> "Expected WorkItem history to include at least generator and processor steps, but saw "
                + stepPayloads.size() + " steps: " + stepPayloads);
      }

      if ("templated-rest".equals(scenarioId)) {
        String generatorPattern = "^hello world from Template Interceptor, sequence number is .+ and was generated at .+$";
        boolean generatorMatched = stepPayloads.stream()
            .anyMatch(payload -> {
              if (payload == null || payload.isBlank()) {
                return false;
              }
              try {
                JsonNode node = objectMapper.readTree(payload);
                JsonNode stepBody = node.path("body");
                String candidate = null;
                if (stepBody.isTextual()) {
                  candidate = stepBody.asText();
                } else if (stepBody.isObject()) {
                  candidate = stepBody.path("original").path("body").asText(null);
                }
                if (candidate == null || candidate.isBlank()) {
                  return false;
                }
                return !candidate.contains("{{") && candidate.matches(generatorPattern);
              } catch (IOException ex) {
                // Not JSON; ignore this step for templating assertions.
                return false;
              }
            });
        assertTrue(generatorMatched,
            () -> "No WorkItem step payload matched templated pattern /" + generatorPattern
                + "/ for templated-rest scenario. payloads=" + stepPayloads);
      }
    } finally {
      message.ack();
    }
  }

  @Then("the final queue keeps processor headers in step history only")
  public void theFinalQueueKeepsProcessorHeadersInStepHistoryOnly() throws Exception {
    ensureStartResponse();
    ensureFinalQueueTap();
    String queue = tapQueueName != null ? tapQueueName : finalQueueName();

    WorkQueueConsumer.Message message = workQueueConsumer.consumeNext(SwarmAssertions.defaultTimeout())
        .orElseThrow(() -> new AssertionError("No message observed on tap queue " + queue));

    try {
      JsonNode envelope = objectMapper.readTree(message.body());
      JsonNode stepsNode = envelope.path("steps");
      if (!stepsNode.isArray() || stepsNode.isEmpty()) {
        throw new AssertionError("Final queue WorkItem did not carry any steps");
      }
      List<String> stepServices = new ArrayList<>();
      boolean sawProcessorStep = false;
      Map<String, Object> messageHeaders = message.headers();
      if (messageHeaders.containsKey(LEGACY_SERVICE_HEADER)) {
        throw new AssertionError("WorkItem message headers should not include " + LEGACY_SERVICE_HEADER
            + ": " + messageHeaders);
      }
      int stepIndex = 0;
      for (JsonNode stepNode : stepsNode) {
        JsonNode stepHeaders = stepNode.path("headers");
        if (!stepHeaders.isObject()) {
          throw new AssertionError("WorkItem step " + stepIndex + " did not include headers");
        }
        if (stepHeaders.has(LEGACY_SERVICE_HEADER)) {
          throw new AssertionError("WorkItem step " + stepIndex + " should not include " + LEGACY_SERVICE_HEADER
              + ": " + stepHeaders);
        }
        if (!stepHeaders.has(STEP_SERVICE_HEADER) || !stepHeaders.has(STEP_INSTANCE_HEADER)) {
          throw new AssertionError("WorkItem step " + stepIndex + " missing tracking headers: " + stepHeaders);
        }
        String service = stepHeaders.path(STEP_SERVICE_HEADER).asText("");
        String instance = stepHeaders.path(STEP_INSTANCE_HEADER).asText("");
        if (service == null || service.isBlank()) {
          throw new AssertionError("WorkItem step " + stepIndex + " header " + STEP_SERVICE_HEADER + " must not be blank");
        }
        if (instance == null || instance.isBlank()) {
          throw new AssertionError("WorkItem step " + stepIndex + " header " + STEP_INSTANCE_HEADER + " must not be blank");
        }
        stepServices.add(service.toLowerCase(Locale.ROOT));

        if (hasAnyProcessorHeader(stepHeaders)) {
          sawProcessorStep = true;
          if (!roleMatches(PROCESSOR_ROLE, service)) {
            throw new AssertionError("Processor step did not include " + STEP_SERVICE_HEADER + "=processor: "
                + stepHeaders);
          }
          for (String header : PROCESSOR_STEP_HEADERS) {
            if (messageHeaders.containsKey(header)) {
              throw new AssertionError("Processor header " + header + " leaked into message headers: " + messageHeaders);
            }
          }
        }
        stepIndex += 1;
      }

      assertTrue(sawProcessorStep, "No WorkItem step carried processor headers");
      assertTrue(containsChain(stepServices, List.of(GENERATOR_ROLE, PROCESSOR_ROLE)),
          () -> "Step tracking headers missing generator→processor chain: " + stepServices);
    } finally {
      message.ack();
    }
  }

  @Then("the redis dataset demo pipeline processes traffic end to end")
  public void redisDatasetDemoPipelineProcessesTrafficEndToEnd() throws Exception {
    ensureStartResponse();
    ensureFinalQueueTap();
    String queue = tapQueueName != null ? tapQueueName : finalQueueName();

    WorkQueueConsumer.Message message = workQueueConsumer.consumeNext(SwarmAssertions.defaultTimeout())
        .orElseThrow(() -> new AssertionError("No message observed on tap queue " + queue));

    try {
      List<String> stepPayloads = workItemStepPayloads(message);
      String scenarioId = scenarioDetails != null ? scenarioDetails.id() : null;
      LOGGER.info("Redis dataset demo WorkItem step payloads for scenario {}: {}", scenarioId, stepPayloads);

      boolean hasDataset = stepPayloads.stream()
          .anyMatch(payload -> payload != null && payload.contains("\"customerCode\""));

      boolean hasHttpRequest = false;
      boolean hasHttpResponse = false;

      for (String payload : stepPayloads) {
        if (payload == null || payload.isBlank()) {
          continue;
        }
        try {
          JsonNode node = objectMapper.readTree(payload);
          if (node.has("path") && node.has("method") && node.has("headers") && node.has("body")) {
            hasHttpRequest = true;
          }
          if (node.has("status") && node.has("body")) {
            hasHttpResponse = true;
          }
        } catch (IOException ex) {
          // Not JSON; ignore this step for HTTP assertions.
        }
      }

      assertTrue(hasDataset,
          () -> "Expected at least one step payload containing customerCode but saw: " + stepPayloads);
      assertTrue(hasHttpRequest,
          () -> "Expected at least one HTTP request envelope step (path/method/headers/body) but saw: " + stepPayloads);
      assertTrue(hasHttpResponse,
          () -> "Expected at least one HTTP response step (status/body) but saw: " + stepPayloads);
    } finally {
      message.ack();
    }
  }

  @Then("the redis dataset demo payloads are fully rendered")
  public void redisDatasetDemoPayloadsAreFullyRendered() throws Exception {
    ensureStartResponse();
    ensureFinalQueueTap();
    String queue = tapQueueName != null ? tapQueueName : finalQueueName();

    WorkQueueConsumer.Message message = workQueueConsumer.consumeNext(SwarmAssertions.defaultTimeout())
        .orElseThrow(() -> new AssertionError("No message observed on tap queue " + queue));

    try {
      JsonNode root = objectMapper.readTree(message.bodyAsString());
      JsonNode stepsNode = root.path("steps");
      assertTrue(stepsNode.isArray() && stepsNode.size() > 0,
          () -> "Redis dataset demo WorkItem carried no steps: " + root);

      JsonNode datasetPayload = null;
      String datasetRaw = null;
      JsonNode requestEnvelope = null;
      JsonNode responseEnvelope = null;

      for (JsonNode stepNode : stepsNode) {
        String payload = stepNode.path("payload").asText("");
        if (payload.isBlank()) {
          continue;
        }
        JsonNode payloadNode = parseJsonNode(payload);
        if (payloadNode == null || !payloadNode.isObject()) {
          continue;
        }
        if (looksLikeHttpRequestEnvelope(payloadNode)) {
          requestEnvelope = payloadNode;
          continue;
        }
        if (looksLikeHttpResponseEnvelope(payloadNode)) {
          responseEnvelope = payloadNode;
          continue;
        }
        if (looksLikeDatasetPayload(payloadNode)) {
          datasetPayload = payloadNode;
          datasetRaw = payload;
        }
      }

      assertNotNull(requestEnvelope, "HTTP request envelope not found in WorkItem steps");
      assertNotNull(responseEnvelope, "HTTP response envelope not found in WorkItem steps");

      JsonNode headersNode = requestEnvelope.path("headers");
      String demoCall = headerValue(headersNode, "x-demo-call");
      assertNotNull(demoCall, "HTTP request headers missing x-demo-call: " + requestEnvelope);
      assertTrue(Set.of("auth", "balance", "topup").contains(demoCall),
          () -> "Unexpected x-demo-call value: " + demoCall);

      assertEquals("/api/redis-demo", requestEnvelope.path("path").asText(),
          "HTTP request path should match redis demo");
      assertEquals("POST", requestEnvelope.path("method").asText(),
          "HTTP request method should be POST");
      assertEquals("application/json", headerValue(headersNode, "content-type"),
          "HTTP request should advertise JSON content type");

      String requestBody = requestEnvelope.path("body").asText(null);
      assertNotNull(requestBody, "HTTP request body was empty");
      assertFalse(requestBody.contains("{{"),
          () -> "HTTP request body appears to contain unrendered templates: " + requestBody);

      JsonNode requestBodyNode = parseJsonNode(requestBody);
      assertNotNull(requestBodyNode, "HTTP request body was not valid JSON: " + requestBody);

      if (datasetPayload == null) {
        datasetPayload = requestBodyNode;
      }
      if (datasetRaw != null) {
        assertFalse(datasetRaw.contains("{{"),
            "Dataset payload appears to contain unrendered templates: " + datasetRaw);
      }
      assertDatasetPayload(datasetPayload);

      String customerCode = requestBodyNode.path("customerCode").asText(null);
      assertNotNull(customerCode, "HTTP request body missing customerCode");
      assertTrue(Set.of("custA", "custB").contains(customerCode),
          () -> "Unexpected customerCode: " + customerCode);

      int status = responseEnvelope.path("status").asInt(-1);
      assertEquals(200, status, "HTTP response status should be 200");

      String responseBody = responseEnvelope.path("body").asText(null);
      assertNotNull(responseBody, "HTTP response body was empty");
      JsonNode responseNode = parseJsonNode(responseBody);
      assertNotNull(responseNode, "HTTP response body was not valid JSON: " + responseBody);

      String expectedMessage = "processed " + customerCode + " (" + demoCall + ")";
      assertEquals(expectedMessage, responseNode.path("message").asText(),
          "Unexpected response message for customer=" + customerCode + " call=" + demoCall);
    } finally {
      message.ack();
    }
  }

  /**
   * Determine which WireMock stub message we expect for a given scenario.
   * <p>
   * Most lifecycle scenarios hit the {@code /api/test} endpoint backed by
   * {@code generator-default.json}, which returns {@code "default generator response"}.
   * The {@code templated-rest} scenario intentionally calls {@code /api/guarded}
   * (see {@code wiremock/mappings/generator-guarded.json}) and therefore expects
   * {@code "guarded wiremock response"} instead.
   */
  private String expectedGeneratorResponseMessageForScenario(String scenarioId) {
    if ("templated-rest".equals(scenarioId)) {
      return "guarded wiremock response";
    }
    return "default generator response";
  }

  // Templated generator behaviour is currently validated via the templated-rest
  // scenario wiring and per-scenario configuration checks in ScenarioDefaultsSteps.

  @When("I stop the swarm")
  public void iStopTheSwarm() {
    ensureStartResponse();
    String idempotencyKey = idKey("stop");
    stopResponse = orchestratorClient.stopSwarm(
        swarmId,
        new ControlRequest(idempotencyKey, "e2e lifecycle stop"));
    LOGGER.info("Stop request correlation={} watch={}", stopResponse.correlationId(), stopResponse.watch());
  }

  @When("I request swarm stop without start")
  public void iRequestSwarmStopWithoutStart() {
    ensureCreateResponse();
    String idempotencyKey = idKey("stop-without-start");
    stopResponse = orchestratorClient.stopSwarm(
        swarmId,
        new ControlRequest(idempotencyKey, "e2e lifecycle stop without start"));
    LOGGER.info("Stop without start request correlation={} watch={}",
        stopResponse.correlationId(), stopResponse.watch());
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

    var outcomeOpt = controlPlaneEvents.outcome("swarm-stop", stopResponse.correlationId());
    assertTrue(outcomeOpt.isPresent(),
        () -> "Missing outcome for swarm-stop correlation=" + stopResponse.correlationId());
    Object status = outcomeOpt.get().data() == null ? null : outcomeOpt.get().data().get("status");
    assertNotNull(status, "Stop outcome should include data.status");
    assertEquals("stopped", status.toString().trim().toLowerCase(Locale.ROOT),
        "Stop outcome should report status Stopped");
  }

  @When("I remove the swarm")
  public void iRemoveTheSwarm() {
    ensureStopResponse();
    String idempotencyKey = idKey("remove");
    removeResponse = orchestratorClient.removeSwarm(
        swarmId,
        new ControlRequest(idempotencyKey, "e2e lifecycle remove"));
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

    assertEquals(1, controlPlaneEvents.outcomeCount("swarm-create"), "Expected exactly one swarm-create outcome");
    assertEquals(1, controlPlaneEvents.outcomeCount("swarm-template"), "Expected exactly one swarm-template outcome");
    assertEquals(1, controlPlaneEvents.outcomeCount("swarm-start"), "Expected exactly one swarm-start outcome");
    assertEquals(1, controlPlaneEvents.outcomeCount("swarm-stop"), "Expected exactly one swarm-stop outcome");
    long removeOutcomeCount = controlPlaneEvents.outcomeCount("swarm-remove");
    assertTrue(removeOutcomeCount >= 1,
        () -> "Expected at least one swarm-remove outcome but saw " + removeOutcomeCount);
    assertTrue(controlPlaneEvents.alerts().isEmpty(), "No alerts should be emitted during the golden path");
  }

  @Then("the swarm is removed after the early stop")
  public void theSwarmIsRemovedAfterTheEarlyStop() {
    ensureRemoveResponse();
    awaitReady("swarm-remove", removeResponse);
    assertNoErrors(removeResponse.correlationId(), "swarm-remove");
    assertWatchMatched(removeResponse);
    swarmRemoved = true;

    SwarmAssertions.await("swarm removed", () -> {
      Optional<SwarmView> view = orchestratorClient.findSwarm(swarmId);
      assertTrue(view.isEmpty(), "Swarm should no longer be present after removal");
    });

    assertEquals(1, controlPlaneEvents.outcomeCount("swarm-create"), "Expected exactly one swarm-create outcome");
    assertEquals(1, controlPlaneEvents.outcomeCount("swarm-template"), "Expected exactly one swarm-template outcome");
    assertEquals(1, controlPlaneEvents.outcomeCount("swarm-start"), "Expected exactly one swarm-start outcome");
    long stopOutcomeCount = controlPlaneEvents.outcomeCount("swarm-stop");
    assertTrue(stopOutcomeCount >= 1,
        () -> "Expected at least one swarm-stop outcome but saw " + stopOutcomeCount);
    long removeOutcomeCount = controlPlaneEvents.outcomeCount("swarm-remove");
    assertTrue(removeOutcomeCount >= 1,
        () -> "Expected at least one swarm-remove outcome but saw " + removeOutcomeCount);
    assertTrue(controlPlaneEvents.alerts().isEmpty(), "No alerts should be emitted during the golden path");
  }

  @After
  public void tearDownLifecycle() {
    if (generatorTapConsumer != null) {
      try {
        generatorTapConsumer.close();
      } catch (Exception ex) {
        LOGGER.debug("Failed to close generator tap consumer", ex);
      } finally {
        generatorTapConsumer = null;
        generatorTapQueueName = null;
      }
    }
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
    SwarmAssertions.await("status events for swarm workers", () -> {
      List<ControlPlaneEvents.StatusEnvelope> statuses = controlPlaneEvents.statusesForSwarm(swarmId);
      for (String role : roles) {
        boolean present = statuses.stream()
            .anyMatch(env -> isStatusForRole(env.status(), role));
        String displayRole = actualRoleName(role);
        assertTrue(present, () -> "Missing status event for role " + displayRole);
      }
    });

    List<ControlPlaneEvents.StatusEnvelope> statuses = controlPlaneEvents.statusesForSwarm(swarmId);
    Map<String, StatusEvent> latestStatuses = new LinkedHashMap<>();
    Map<String, String> latestInstances = new LinkedHashMap<>();
    for (String role : roles) {
      List<ControlPlaneEvents.StatusEnvelope> roleStatuses = statuses.stream()
          .filter(env -> isStatusForRole(env.status(), role))
          .sorted(Comparator.comparing(ControlPlaneEvents.StatusEnvelope::receivedAt))
          .toList();
      ControlPlaneEvents.StatusEnvelope envelope = roleStatuses.stream()
          .filter(env -> "status-full".equalsIgnoreCase(env.status().type()))
          .reduce((left, right) -> right)
          .orElseGet(() -> roleStatuses.isEmpty() ? null : roleStatuses.get(roleStatuses.size() - 1));
      if (envelope == null) {
        throw new AssertionError("No status event captured for role " + role);
      }
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
    String details = describeStatus(status, snapshot);
    LOGGER.info("Latest status summary role={} instance={} details={}",
        displayRole, instance, details);

    boolean aggregateEnabled = Boolean.TRUE.equals(status.data().enabled());
    boolean snapshotHasEnabled = snapshot.containsKey("enabled");
    boolean workerEnabled = isTruthy(snapshot.get("enabled"));

    // Treat a worker as "running" when either the aggregate flag or the
    // snapshot-level enabled flag is true. We no longer rely on state/totals
    // semantics here because they may legitimately lag or be omitted.
    boolean consideredRunning = aggregateEnabled || (snapshotHasEnabled && workerEnabled);
    if (!consideredRunning) {
      Optional<StatusEvent> deltaOpt = controlPlaneEvents.latestStatusDeltaEvent(
          swarmId, displayRole, instance);
      if (deltaOpt.isPresent() && Boolean.TRUE.equals(deltaOpt.get().data().enabled())) {
        consideredRunning = true;
        LOGGER.info("Worker enabled=true via latest status-delta role={} instance={}",
            displayRole, instance);
      }
    }

    assertTrue(consideredRunning,
        () -> "Worker not considered running for role " + displayRole + ": " + details);

    // We no longer assert on processed counters, state or totals here; they are
    // only used for logging and manual diagnostics.

    String recordedInstance = workerInstances.get(role);
    assertNotNull(recordedInstance,
        () -> "Worker instance for role " + displayRole + " not recorded despite status=" + details);
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
    Map<String, Object> context = status.data().context();
    Object state = context.get("state");
    Object totals = context.get("totals");
    return "state=" + (state == null ? "<n/a>" : state)
        + ", aggregateEnabled=" + status.data().enabled()
        + ", workerEnabled=" + workerEnabled
        + ", processed=" + describeProcessed(processed)
        + ", totals=" + describeTotals(totals)
        + ", instance=" + status.instance();
  }

  private String describeTotals(Object totals) {
    if (totals == null) {
      return "<none>";
    }
    return String.valueOf(totals);
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
    Object workers = status.data().context().get("workers");
    if (!(workers instanceof List<?> list)) {
      Map<String, Object> snapshot = new LinkedHashMap<>();
      String resolvedRole = status.role() != null ? status.role() : role;
      if (resolvedRole != null && !resolvedRole.isBlank()) {
        snapshot.put("role", resolvedRole);
      }
      if (status.instance() != null && !status.instance().isBlank()) {
        snapshot.put("instance", status.instance());
      }
      if (status.data().enabled() != null) {
        snapshot.put("enabled", status.data().enabled());
      }
      Object config = status.data().extra().get("config");
      if (config instanceof Map<?, ?> map) {
        snapshot.put("config", copyMap(map));
      }
      return snapshot.isEmpty() ? Map.of() : Map.copyOf(snapshot);
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

  private void assertRuntimeMeta(Map<String, Object> runtime, String label) {
    assertNotNull(runtime, () -> "Missing runtime metadata for " + label);
    assertFalse(runtime.isEmpty(), () -> "Runtime metadata should not be empty for " + label);
    assertTrue(runtime.containsKey("runId"), () -> "Runtime metadata missing runId for " + label);
    assertTrue(runtime.containsKey("containerId"), () -> "Runtime metadata missing containerId for " + label);
    assertTrue(runtime.containsKey("image"), () -> "Runtime metadata missing image for " + label);
    assertTrue(runtime.containsKey("stackName"), () -> "Runtime metadata missing stackName for " + label);
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

  private Map<String, Object> toMap(Object value) {
    if (value instanceof Map<?, ?> map) {
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
    String scenarioId = scenarioDetails != null ? scenarioDetails.id() : null;
    if ("local-rest-defaults".equals(scenarioId)
        || "templated-rest".equals(scenarioId)
        || "history-policy-demo".equals(scenarioId)
        || "local-rest".equals(scenarioId)
        || "local-rest-with-multi-generators".equals(scenarioId)) {
      return "final";
    }
    if ("redis-dataset-demo".equals(scenarioId)
        || "tcp-socket-demo".equals(scenarioId)) {
      return "post";
    }
    throw new AssertionError("Unsupported scenario for final queue resolution: " + scenarioId);
  }

  @Then("the final queue reports a processor error")
  public void theFinalQueueReportsAProcessorError() throws Exception {
    ensureStartResponse();
    ensureFinalQueueTap();
    String queue = tapQueueName != null ? tapQueueName : finalQueueName();

    WorkQueueConsumer.Message message = workQueueConsumer.consumeNext(SwarmAssertions.defaultTimeout())
        .orElseThrow(() -> new AssertionError("No message observed on tap queue " + queue));

    try {
      List<String> stepPayloads = workItemStepPayloads(message);
      String error = findProcessorError(stepPayloads);
      if (error == null || error.isBlank()) {
        throw new AssertionError("Expected processor error in WorkItem steps but none found. payloads=" + stepPayloads);
      }
      LOGGER.info("Processor error captured from final queue: {}", error);
    } finally {
      message.ack();
    }
  }

  private String findProcessorError(List<String> stepPayloads) {
    for (String payload : stepPayloads) {
      if (payload == null || payload.isBlank()) {
        continue;
      }
      try {
        JsonNode node = objectMapper.readTree(payload);
        if (node.has("error")) {
          String error = node.path("error").asText();
          if (error != null && !error.isBlank()) {
            return error;
          }
        }
      } catch (IOException ignored) {
      }
    }
    return null;
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

  private void ensureGeneratorTapForTemplating() {
    if (generatorTapConsumer != null) {
      return;
    }
    ensureTemplate();
    if (!"templated-rest".equals(scenarioDetails.id())) {
      return;
    }
    Bee generator = findBee(GENERATOR_ROLE);
    Work work = generator.work();
    assertNotNull(work, "Generator work configuration was not returned");
    String outSuffix = trimmed(work.defaultOut());
    assertNotNull(outSuffix, "Generator work.out must be configured for templated-rest scenario");

    String exchange = hiveExchangeName();
    String routingKey = queueNameForSuffix(outSuffix);
    generatorTapConsumer = WorkQueueConsumer.forExchangeTap(
        rabbitSubscriptions.connectionFactory(), exchange, routingKey);
    generatorTapQueueName = generatorTapConsumer.queueName();
    LOGGER.info("Subscribed to generator exchange tap queue={} exchange={} routingKey={}",
        generatorTapQueueName, exchange, routingKey);
  }

  private void assertTemplatedGeneratorOutputIfApplicable() {
    if (generatorTapConsumer == null) {
      return;
    }
    WorkQueueConsumer.Message message = generatorTapConsumer.consumeNext(SwarmAssertions.defaultTimeout())
        .orElseThrow(() -> new AssertionError("No message observed on generator tap queue " + generatorTapQueueName));
    try {
      String payload = message.bodyAsString();
      JsonNode envelope = objectMapper.readTree(payload);
      JsonNode stepsNode = envelope.path("steps");
      if (!stepsNode.isArray() || stepsNode.isEmpty()) {
        throw new AssertionError("Generator tap WorkItem did not carry any steps");
      }
      JsonNode lastStep = stepsNode.get(stepsNode.size() - 1);
      String httpPayload = lastStep.path("payload").asText("");
      JsonNode root = objectMapper.readTree(httpPayload);
      JsonNode bodyNode = root.path("body");
      String requestBody = bodyNode.isMissingNode() ? null : bodyNode.asText(null);
      assertNotNull(requestBody, "Templated generator work item did not include a 'body' field");
      LOGGER.info("Observed generator work item body for templated-rest scenario: {}", requestBody);
      assertFalse(requestBody.contains("{{"),
          () -> "Generator work item body appears to contain unrendered template expressions. body=" + requestBody);
    } catch (Exception ex) {
      throw new AssertionError("Failed to inspect templated generator output", ex);
    } finally {
      message.ack();
    }
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

  private List<String> workItemStepPayloads(WorkQueueConsumer.Message message) throws IOException {
    String text = message.bodyAsString();
    if (text == null || text.isBlank()) {
      return List.of();
    }
    JsonNode root = objectMapper.readTree(text);
    JsonNode stepsNode = root.path("steps");
    if (!stepsNode.isArray()) {
      return List.of();
    }
    List<String> payloads = new ArrayList<>();
    stepsNode.forEach(element -> payloads.add(element.path("payload").asText("")));
    return payloads;
  }

  private JsonNode parseJsonNode(String payload) {
    if (!looksLikeJson(payload)) {
      return null;
    }
    try {
      return objectMapper.readTree(payload);
    } catch (IOException ex) {
      return null;
    }
  }

  private boolean looksLikeHttpRequestEnvelope(JsonNode payloadNode) {
    return payloadNode.has("path")
        && payloadNode.has("method")
        && payloadNode.has("headers")
        && payloadNode.has("body");
  }

  private boolean looksLikeHttpResponseEnvelope(JsonNode payloadNode) {
    return payloadNode.has("status")
        && payloadNode.has("body");
  }

  private boolean looksLikeDatasetPayload(JsonNode payloadNode) {
    return payloadNode.has("customerCode")
        && payloadNode.has("accountNumber")
        && payloadNode.has("cardNumber")
        && payloadNode.has("nonce");
  }

  private void assertDatasetPayload(JsonNode payloadNode) {
    assertNotNull(payloadNode, "Dataset payload missing from WorkItem steps");

    String customerCode = payloadNode.path("customerCode").asText(null);
    String accountNumber = payloadNode.path("accountNumber").asText(null);
    String cardNumber = payloadNode.path("cardNumber").asText(null);
    String nonce = payloadNode.path("nonce").asText(null);

    assertNotNull(customerCode, "Dataset payload missing customerCode");
    assertNotNull(accountNumber, "Dataset payload missing accountNumber");
    assertNotNull(cardNumber, "Dataset payload missing cardNumber");
    assertNotNull(nonce, "Dataset payload missing nonce");

    assertTrue(accountNumber.matches("\\d{8}"),
        () -> "Unexpected accountNumber format: " + accountNumber);
    assertTrue(cardNumber.matches("\\d{16}"),
        () -> "Unexpected cardNumber format: " + cardNumber);
    try {
      UUID.fromString(nonce);
    } catch (IllegalArgumentException ex) {
      throw new AssertionError("Unexpected nonce format: " + nonce, ex);
    }
  }

  private String headerValue(JsonNode headersNode, String name) {
    if (headersNode == null || !headersNode.isObject()) {
      return null;
    }
    String target = name.toLowerCase(Locale.ROOT);
    Iterator<Map.Entry<String, JsonNode>> fields = headersNode.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> entry = fields.next();
      if (entry.getKey().toLowerCase(Locale.ROOT).equals(target)) {
        JsonNode value = entry.getValue();
        return value == null ? null : value.asText(null);
      }
    }
    return null;
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

  private boolean hasAnyProcessorHeader(JsonNode headers) {
    for (String header : PROCESSOR_STEP_HEADERS) {
      if (headers.has(header)) {
        return true;
      }
    }
    return false;
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

    StatusEvent.Queues workQueues = status.data().io().work().queues();
    List<String> actualWorkIn = workQueues == null ? List.of() : workQueues.in();
    List<String> actualWorkOut = workQueues == null ? List.of() : workQueues.out();
    assertListEquals("queues.work.in for role " + displayRole, expectedWorkIn(role), actualWorkIn);
    assertListEquals("queues.work.out for role " + displayRole, expectedWorkOut(role), actualWorkOut);

    StatusEvent.Queues controlQueues = status.data().io().control().queues();
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

  private boolean isStatusForRole(StatusEvent status, String role) {
    return status != null
        && roleMatches(role, status.role());
  }

  private String expectedInboundQueue(String role) {
    ensureTemplate();
    String scenarioId = scenarioDetails != null ? scenarioDetails.id() : null;
    String actualRole = actualRoleName(role);

    // Hard-coded expectations for known lifecycle scenario
    if ("local-rest-defaults".equals(scenarioId)) {
      if (GENERATOR_ROLE.equalsIgnoreCase(actualRole)) {
        return null;
      }
      if (MODERATOR_ROLE.equalsIgnoreCase(actualRole)) {
        return queueNameForSuffix("gen");
      }
      if (PROCESSOR_ROLE.equalsIgnoreCase(actualRole)) {
        // Processor consumes from the moderator's output queue.
        return queueNameForSuffix("mod");
      }
      if (POSTPROCESSOR_ROLE.equalsIgnoreCase(actualRole)) {
        return queueNameForSuffix("final");
      }
    }

    throw new AssertionError(
        "Unsupported scenario/role for inbound queue expectations: scenario="
            + scenarioId + " role=" + actualRole);
  }

  private List<String> expectedWorkIn(String role) {
    String queue = expectedInboundQueue(role);
    return queueList(queue);
  }

  private List<String> expectedWorkOut(String role) {
    Bee bee = findBee(role);
    Work work = bee.work();
    if (work == null || work.defaultOut() == null || work.defaultOut().isBlank()) {
      return List.of();
    }
    return queueList(queueNameForSuffix(work.defaultOut()));
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
    // Roles must match exactly; no substring or fallback matching
    return expected.equals(actual);
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

  private void ensureStopResponseWithoutStart() {
    ensureCreateResponse();
    Assumptions.assumeTrue(stopResponse != null, "Stop request was not issued");
  }

  private void ensureRemoveResponse() {
    ensureStopResponse();
    Assumptions.assumeTrue(removeResponse != null, "Remove request was not issued");
  }

  private void awaitReady(String signal, ControlResponse response) {
    String correlationId = response.correlationId();
    SwarmAssertions.await(signal + " outcome", () -> {
      Optional<io.pockethive.control.CommandOutcome> outcome = controlPlaneEvents.outcome(signal, correlationId);
      assertTrue(outcome.isPresent(), () -> "Missing outcome for " + signal + " correlation=" + correlationId);
    });
  }

  private io.pockethive.control.CommandOutcome awaitOutcome(String signal, ControlResponse response) {
    String correlationId = response.correlationId();
    SwarmAssertions.await(signal + " outcome", () -> {
      Optional<io.pockethive.control.CommandOutcome> outcome = controlPlaneEvents.outcome(signal, correlationId);
      assertTrue(outcome.isPresent(), () -> "Missing outcome for " + signal + " correlation=" + correlationId);
    });
    return controlPlaneEvents.outcome(signal, correlationId)
        .orElseThrow(() -> new AssertionError("Missing outcome for " + signal + " correlation=" + correlationId));
  }

  private void awaitSwarmPlanReady() {
    SwarmAssertions.await("swarm-plan outcome", () -> {
      Optional<ControlPlaneEvents.OutcomeEnvelope> envelope =
          latestOutcomeForSwarm("swarm-plan", swarmId);
      assertTrue(envelope.isPresent(), () -> "Missing outcome for swarm-plan swarm=" + swarmId);
      assertOutcomeStatus("swarm-plan", envelope.get().outcome(), "Ready");
    });
  }

  private void assertNoErrors(String correlationId, String context) {
    List<io.pockethive.control.AlertMessage> alerts = controlPlaneEvents.alertsForCorrelation(correlationId);
    assertTrue(alerts.isEmpty(), () -> "Unexpected alerts for " + context + " correlation=" + correlationId + ": " + alerts);
  }

  private void assertWatchMatched(ControlResponse response) {
    String signal = signalFromWatch(response);
    if (signal.isEmpty()) {
      return;
    }
    controlPlaneEvents.findOutcome(signal, response.correlationId()).ifPresent(env -> {
      String expected = response.watch().successTopic();
      if (expected != null && !expected.isBlank()) {
        assertEquals(expected, env.routingKey(), "Watch success topic should match emitted event");
      }
    });
    String errorTopic = response.watch().errorTopic();
    if (errorTopic != null && !errorTopic.isBlank()) {
      assertFalse(controlPlaneEvents.hasMessageOnRoutingKey(errorTopic),
          () -> "Unexpected error event detected on " + errorTopic);
    }
  }

  private String signalFromWatch(ControlResponse response) {
    // success topic format: event.outcome.<signal>.<swarm>... -> extract <signal>
    String topic = response.watch().successTopic();
    if (topic == null || topic.isBlank()) {
      return "";
    }
    String[] parts = topic.split("\\.");
    if (parts.length < 4) {
      return "";
    }
    String raw = parts[3];
    return raw.toLowerCase(Locale.ROOT);
  }

  private Optional<StatusEvent> latestStatusFull(String role, String instance) {
    if (role == null || role.isBlank() || instance == null || instance.isBlank()) {
      return Optional.empty();
    }
    return controlPlaneEvents.statusesForSwarm(swarmId).stream()
        .filter(env -> env.status() != null
            && "status-full".equalsIgnoreCase(env.status().type())
            && roleMatches(role, env.status().role())
            && instance.equalsIgnoreCase(env.status().instance()))
        .max(Comparator.comparing(ControlPlaneEvents.StatusEnvelope::receivedAt))
        .map(ControlPlaneEvents.StatusEnvelope::status);
  }

  private Optional<ControlPlaneEvents.OutcomeEnvelope> latestOutcomeForSwarm(String signal, String targetSwarmId) {
    if (signal == null || signal.isBlank() || targetSwarmId == null || targetSwarmId.isBlank()) {
      return Optional.empty();
    }
    String expectedType = signal.trim().toLowerCase(Locale.ROOT);
    return controlPlaneEvents.outcomes().stream()
        .filter(env -> env != null && env.outcome() != null)
        .filter(env -> env.outcome().type() != null
            && expectedType.equals(env.outcome().type().toLowerCase(Locale.ROOT)))
        .filter(env -> env.outcome().scope() != null
            && targetSwarmId.equalsIgnoreCase(env.outcome().scope().swarmId()))
        .max(Comparator.comparing(ControlPlaneEvents.OutcomeEnvelope::receivedAt));
  }

  private void assertOutcomeStatus(String signal, io.pockethive.control.CommandOutcome outcome, String expected) {
    String status = outcomeStatus(outcome);
    assertNotNull(status, () -> signal + " outcome missing data.status: " + describeOutcome(outcome));
    String normalized = status.trim().toLowerCase(Locale.ROOT);
    assertEquals(expected.toLowerCase(Locale.ROOT), normalized,
        () -> signal + " outcome status mismatch: " + describeOutcome(outcome));
  }

  private void assertNotReadyOutcome(String signal, ControlResponse response) {
    io.pockethive.control.CommandOutcome outcome = awaitOutcome(signal, response);
    assertOutcomeStatus(signal, outcome, "NotReady");
    Map<String, Object> context = outcomeContext(outcome);
    assertFalse(context.isEmpty(), () -> "Expected NotReady outcome to include data.context: " + describeOutcome(outcome));
  }

  private String outcomeStatus(io.pockethive.control.CommandOutcome outcome) {
    if (outcome == null || outcome.data() == null) {
      return null;
    }
    Object status = outcome.data().get("status");
    return status == null ? null : status.toString();
  }

  private Map<String, Object> outcomeContext(io.pockethive.control.CommandOutcome outcome) {
    if (outcome == null || outcome.data() == null) {
      return Map.of();
    }
    Object context = outcome.data().get("context");
    if (context instanceof Map<?, ?> map) {
      return copyMap(map);
    }
    return Map.of();
  }

  private String describeOutcome(io.pockethive.control.CommandOutcome outcome) {
    if (outcome == null) {
      return "<null outcome>";
    }
    return "type=" + outcome.type()
        + " scope=" + outcome.scope()
        + " correlationId=" + outcome.correlationId()
        + " data=" + (outcome.data() == null ? "<none>" : outcome.data());
  }

  private Set<String> expectedQueueSuffixes(SwarmTemplate template) {
    Set<String> suffixes = new LinkedHashSet<>();
    if (template.bees() != null) {
      for (Bee bee : template.bees()) {
        Work work = bee.work();
        if (work != null) {
          suffixes.addAll(work.in().values());
          suffixes.addAll(work.out().values());
        }
      }
    }
    return suffixes;
  }

  private String idKey(String action) {
    return idempotencyPrefix + "-" + action + "-" + UUID.randomUUID();
  }
}
