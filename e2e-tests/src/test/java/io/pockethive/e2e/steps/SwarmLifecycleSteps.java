package io.pockethive.e2e.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.cucumber.java.After;
import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.pockethive.control.ErrorConfirmation;
import io.pockethive.control.ReadyConfirmation;
import io.pockethive.e2e.clients.OrchestratorClient;
import io.pockethive.e2e.clients.OrchestratorClient.ControlRequest;
import io.pockethive.e2e.clients.OrchestratorClient.ControlResponse;
import io.pockethive.e2e.clients.OrchestratorClient.SwarmCreateRequest;
import io.pockethive.e2e.clients.OrchestratorClient.SwarmView;
import io.pockethive.e2e.clients.RabbitSubscriptions;
import io.pockethive.e2e.clients.ScenarioManagerClient;
import io.pockethive.e2e.clients.ScenarioManagerClient.ScenarioDetails;
import io.pockethive.e2e.clients.ScenarioManagerClient.ScenarioSummary;
import io.pockethive.e2e.config.EnvironmentConfig;
import io.pockethive.e2e.config.EnvironmentConfig.ServiceEndpoints;
import io.pockethive.e2e.support.ControlPlaneEvents;
import io.pockethive.e2e.support.QueueProbe;
import io.pockethive.e2e.support.SwarmAssertions;
import io.pockethive.swarm.model.Bee;
import io.pockethive.swarm.model.SwarmTemplate;
import io.pockethive.swarm.model.Work;

/**
 * Step definitions for the Phase 2 swarm lifecycle golden path scenario.
 */
public class SwarmLifecycleSteps {

  private static final Logger LOGGER = LoggerFactory.getLogger(SwarmLifecycleSteps.class);
  private ServiceEndpoints endpoints;
  private OrchestratorClient orchestratorClient;
  private ScenarioManagerClient scenarioManagerClient;
  private RabbitSubscriptions rabbitSubscriptions;
  private ControlPlaneEvents controlPlaneEvents;
  private ScenarioDetails scenarioDetails;
  private SwarmTemplate template;
  private String swarmId;
  private String idempotencyPrefix;
  private boolean swarmRemoved;

  private ControlResponse createResponse;
  private ControlResponse startResponse;
  private ControlResponse stopResponse;
  private ControlResponse removeResponse;

  @Given("the swarm lifecycle harness is initialised")
  public void theSwarmLifecycleHarnessIsInitialised() {
    try {
      endpoints = EnvironmentConfig.loadServiceEndpoints();
    } catch (IllegalStateException ex) {
      Assumptions.assumeTrue(false, () -> "Skipping lifecycle scenario: " + ex.getMessage());
    }

    orchestratorClient = OrchestratorClient.create(endpoints.orchestratorBaseUrl());
    scenarioManagerClient = ScenarioManagerClient.create(endpoints.scenarioManagerBaseUrl());
    rabbitSubscriptions = RabbitSubscriptions.from(endpoints.rabbitMq());
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
      assertFalse(view.get().workEnabled(), "Workloads should be disabled after stop");
    });
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

  private void ensureHarness() {
    Assumptions.assumeTrue(endpoints != null, "Harness not initialised");
  }

  private void ensureTemplate() {
    ensureHarness();
    Assumptions.assumeTrue(template != null, "Scenario template not loaded");
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
