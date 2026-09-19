package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandOutcome;
import io.pockethive.control.CommandResult;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.SwarmStartupArtifactReference;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.Health;
import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.RuntimeMetadata;
import io.pockethive.swarm.model.lifecycle.RuntimeResourceState;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SwarmOperationObservationHandlerTest {

  private static final String SWARM_ID = "swarm-test";
  private static final String CONTROLLER = "controller-1";
  private static final String DIGEST = "a".repeat(64);
  private static final Target CONTROLLER_TARGET = new Target("swarm-controller", CONTROLLER);

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final SwarmStore store = new SwarmStore();
  private final SwarmOperationCoordinator operations = new SwarmOperationCoordinator();
  private final CapturingPublisher transport = new CapturingPublisher();
  private SwarmOperationObservationHandler handler;

  @BeforeEach
  void setUp() {
    Swarm swarm = new Swarm(SWARM_ID, CONTROLLER, "container-1", "run-1", NetworkMode.DIRECT);
    swarm.attachStartupArtifact(new SwarmStartupArtifactReference("runtime/startup.json", DIGEST));
    store.register(swarm);
    handler = new SwarmOperationObservationHandler(
        store, operations, new OperationOutcomePublisher(transport, "orchestrator-1"));
  }

  @Test
  void readyControllerWithMatchingArtifactCompletesCreate() throws Exception {
    Instant now = Instant.now();
    operations.reserve(
        SWARM_ID, OperationType.CREATE, CONTROLLER_TARGET,
        new RuntimeMetadata("template-1", "run-1"),
        "create-corr", "create-idem", now, now.plusSeconds(30));
    operations.markDispatched("create-corr", now.plusMillis(1));
    var status = mapper.readTree("""
        {"data":{"context":{
          "startupReady":true,
          "startupArtifactSha256":"%s",
          "controllerState":"READY",
          "workloadState":"STOPPED"
        }}}
        """.formatted(DIGEST));

    handler.handleControllerStatusFull(
        SWARM_ID, CONTROLLER, status);

    assertThat(operations.findByCorrelation("create-corr"))
        .map(operation -> operation.state())
        .contains(OperationState.SUCCEEDED);
    assertThat(transport.events).singleElement().satisfies(event -> {
      assertThat(event.routingKey())
          .isEqualTo("event.outcome.swarm-create.swarm-test.orchestrator.orchestrator-1");
      assertThat(event.payload()).isInstanceOf(CommandOutcome.class);
    });
  }

  @Test
  void configUpdateWaitsForFreshMatchingWorkerObservation() {
    Target worker = new Target("generator", "generator-1");
    Instant now = Instant.now().minusSeconds(2);
    operations.reserve(
        SWARM_ID, OperationType.CONFIG_UPDATE, worker,
        new RuntimeMetadata("template-1", "run-1"),
        "config-corr", "config-idem", now, now.plusSeconds(60));
    operations.markDispatched("config-corr", now.plusSeconds(1));
    operations.registerConfigExpectation(
        "config-corr", SwarmOperationCoordinator.ConfigEnabledExpectation.ENABLED);
    CommandResult result = new CommandResult(
        Instant.now(), "2", "result", "config-update", worker.instance(),
        new ControlScope(SWARM_ID, worker.role(), worker.instance()),
        "config-corr", "config-idem",
        Map.of("templateId", "template-1", "runId", "run-1"),
        new TerminalResult(TerminalStatus.SUCCEEDED, false, Map.of(
            "target", worker,
            "requestedEnabled", true,
            "observedEnabled", true,
            "appliedConfigSha256", "b".repeat(64))));
    assertThat(handler.awaitConfigObservation(result)).isTrue();
    assertThat(operations.findByCorrelation("config-corr"))
        .map(operation -> operation.state())
        .contains(OperationState.DISPATCHED);

    store.find(SWARM_ID).orElseThrow().updateObservation(
        ControllerState.READY, WorkloadState.RUNNING, Health.HEALTHY,
        RuntimeResourceState.PRESENT,
        Map.of("workers", List.of(Map.of(
            "role", worker.role(),
            "instance", worker.instance(),
            "enabled", true,
            "lastSeenAt", Instant.now().toString()))),
        Instant.now());
    handler.handleControllerObservation(SWARM_ID);

    assertThat(operations.findByCorrelation("config-corr"))
        .map(operation -> operation.state())
        .contains(OperationState.SUCCEEDED);
    assertThat(transport.events).singleElement().satisfies(event ->
        assertThat(event.routingKey())
            .isEqualTo("event.outcome.config-update.swarm-test.orchestrator.orchestrator-1"));
  }

  private static final class CapturingPublisher implements ControlPlanePublisher {
    private final List<EventMessage> events = new ArrayList<>();

    @Override
    public void publishSignal(SignalMessage message) {
    }

    @Override
    public void publishEvent(EventMessage event) {
      events.add(event);
    }
  }
}
