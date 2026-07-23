package io.pockethive.orchestrator.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.docker.compute.PocketHiveDockerLabels;
import io.pockethive.manager.runtime.ComputeAdapterType;
import io.pockethive.orchestrator.app.ContainerLifecycleManager;
import io.pockethive.orchestrator.app.SwarmLifecycleCommandService;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmOperationCoordinator;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.ExecuteRequest;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.Plan;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.PlanRequest;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeInventoryPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeRemovalPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitQueueResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitTopologyPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RuntimeOwnershipManifestStore;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.RuntimeIntent;
import io.pockethive.swarm.model.lifecycle.Target;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RuntimeReconciliationServiceTest {

  private final SwarmStore swarms = new SwarmStore();
  private final RuntimeOwnershipManifestStore manifests = mock(RuntimeOwnershipManifestStore.class);
  private final ComputeRuntimeInventoryPort inventory = mock(ComputeRuntimeInventoryPort.class);
  private final ComputeRuntimeRemovalPort computeRemoval = mock(ComputeRuntimeRemovalPort.class);
  private final RabbitTopologyPort rabbit = mock(RabbitTopologyPort.class);
  private final SwarmLifecycleCommandService lifecycleCommands = mock(SwarmLifecycleCommandService.class);
  private final ContainerLifecycleManager lifecycleManager = mock(ContainerLifecycleManager.class);
  private final RuntimeCleanupEvidenceStore evidence = new RuntimeCleanupEvidenceStore();
  private RuntimeReconciliationService service;

  @BeforeEach
  void setUp() {
    when(lifecycleManager.currentComputeAdapterType()).thenReturn(ComputeAdapterType.DOCKER_SINGLE);
    when(inventory.list()).thenReturn(List.of());
    when(manifests.find(any(), any())).thenReturn(Optional.empty());
    when(manifests.findLatest(any())).thenReturn(Optional.empty());
    service = new RuntimeReconciliationService(
        swarms, manifests, inventory, computeRemoval, rabbit, evidence,
        lifecycleManager, lifecycleCommands, controlPlaneProperties());
  }

  @Test
  void registeredSwarmHasOnlyCanonicalLifecycleCandidateAndDirectResourcesStayBlocked() {
    swarms.register(new Swarm("sw1", "controller-1", "manager-1", "run-1"));
    when(inventory.list()).thenReturn(List.of(runtime("manager-1", "manager", "swarm-controller", "controller-1")));
    when(manifests.find("sw1", "run-1")).thenReturn(Optional.of(manifest()));
    when(rabbit.queue("ph.sw1.work")).thenReturn(Optional.of(new RabbitQueueResource("ph.sw1.work", 0, 0)));

    Plan plan = service.plan(new PlanRequest("sw1", "run-1", true, true));

    assertThat(plan.candidates()).extracting("candidateId")
        .containsExactly("lifecycle:swarm:sw1");
    assertThat(plan.blocked()).extracting("candidateId")
        .contains("docker:container:manager-1", "rabbit:queue:ph.sw1.work");
  }

  @Test
  void registeredSwarmWithAbsentIntentExposesNoSecondRemove() {
    Swarm swarm = new Swarm("sw1", "controller-1", "manager-1", "run-1");
    swarm.requestRuntime(RuntimeIntent.ABSENT);
    swarms.register(swarm);

    Plan plan = service.plan(new PlanRequest("sw1", "run-1", false, false));

    assertThat(plan.candidates()).isEmpty();
    assertThat(plan.blocked()).singleElement()
        .extracting("reason").asString().contains("active REMOVE operation");
  }

  @Test
  void lifecycleCleanupDispatchesCanonicalRemoveAndReturnsOperationIdentity() {
    swarms.register(new Swarm("sw1", "controller-1", "manager-1", "run-1"));
    Plan plan = service.plan(new PlanRequest("sw1", "run-1", false, false));
    SwarmOperationCoordinator coordinator = new SwarmOperationCoordinator();
    Instant now = Instant.now();
    var reservation = coordinator.reserve(
        "sw1", OperationType.REMOVE, new Target("swarm-controller", "controller-1"),
        "corr-remove", "cleanup-idem", now, now.plusSeconds(180));
    coordinator.markDispatched("corr-remove", now.plusMillis(1));
    when(lifecycleCommands.dispatch(
        eq(ControlPlaneSignals.SWARM_REMOVE), eq("sw1"), eq("cleanup-idem"), any(Duration.class)))
        .thenReturn(new SwarmOperationCoordinator.Reservation(
            coordinator.findByCorrelation("corr-remove").orElseThrow(), reservation.reused()));

    var response = service.execute(new ExecuteRequest(
        "sw1", "run-1", false, false,
        plan.candidateSetHash(), List.of("lifecycle:swarm:sw1"),
        "cleanup-idem", "operator cleanup", "alice"));

    var result = response.evidence().resultByCandidate().getFirst();
    assertThat(result.status()).isEqualTo(RuntimeCleanupStatus.DISPATCHED);
    assertThat(result.correlationId()).isEqualTo("corr-remove");
    assertThat(result.operationUrl()).isEqualTo("/api/swarms/sw1/operations/corr-remove");
    verify(computeRemoval, never()).removeContainer(any());
    verify(rabbit, never()).deleteQueue(any());
  }

  @Test
  void exactUnregisteredLabeledRuntimeCanStillBeRemovedSynchronously() {
    when(inventory.list()).thenReturn(List.of(runtime("worker-1", "worker", "generator", "generator-1")));
    Plan plan = service.plan(new PlanRequest("sw1", "run-1", false, false));
    String candidateId = plan.candidates().getFirst().candidateId();

    var first = service.execute(new ExecuteRequest(
        "sw1", "run-1", false, false,
        plan.candidateSetHash(), List.of(candidateId),
        "orphan-idem", "orphan cleanup", "alice"));
    var replay = service.execute(new ExecuteRequest(
        "sw1", "run-1", false, false,
        plan.candidateSetHash(), List.of(candidateId),
        "orphan-idem", "orphan cleanup", "alice"));

    assertThat(first.evidence().resultByCandidate().getFirst().status())
        .isEqualTo(RuntimeCleanupStatus.REMOVED);
    assertThat(replay.idempotent()).isTrue();
    verify(computeRemoval).removeContainer("worker-1");
  }

  @Test
  void staleCandidateHashFailsBeforeMutation() {
    when(inventory.list()).thenReturn(List.of(runtime("worker-1", "worker", "generator", "generator-1")));
    Plan plan = service.plan(new PlanRequest("sw1", "run-1", false, false));

    assertThatThrownBy(() -> service.execute(new ExecuteRequest(
        "sw1", "run-1", false, false,
        "sha256:stale", List.of(plan.candidates().getFirst().candidateId()),
        "stale-idem", "stale plan", "alice")))
        .isInstanceOf(RuntimeCleanupException.class);
    verify(computeRemoval, never()).removeContainer(any());
  }

  private static ComputeRuntimeResource runtime(
      String id, String resourceKind, String role, String instance) {
    return new ComputeRuntimeResource(
        id,
        RuntimeCleanupPorts.RUNTIME_TYPE_CONTAINER,
        id,
        "image:latest",
        "exited",
        Map.of(
            PocketHiveDockerLabels.MANAGED, PocketHiveDockerLabels.MANAGED_VALUE,
            PocketHiveDockerLabels.SWARM_ID, "sw1",
            PocketHiveDockerLabels.RUN_ID, "run-1",
            PocketHiveDockerLabels.RESOURCE_KIND, resourceKind,
            PocketHiveDockerLabels.ROLE, role,
            PocketHiveDockerLabels.INSTANCE, instance));
  }

  private static RuntimeOwnershipManifest manifest() {
    return new RuntimeOwnershipManifest(
        "sw1", "run-1", "template-1", "DOCKER_SINGLE", Instant.now(), List.of(),
        new RuntimeOwnershipManifest.RabbitResources(
            List.of(), List.of("ph.sw1.work"), List.of()));
  }

  private static ControlPlaneProperties controlPlaneProperties() {
    ControlPlaneProperties properties = new ControlPlaneProperties();
    properties.setControlQueuePrefix("ph.control");
    properties.setSwarmId("ALL");
    properties.setInstanceId("orchestrator-1");
    return properties;
  }
}
