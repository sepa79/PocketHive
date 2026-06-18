package io.pockethive.orchestrator.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;

import io.pockethive.controlplane.spring.ControlPlaneProperties;
import io.pockethive.orchestrator.app.ContainerLifecycleManager;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.Plan;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.PlanRequest;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.ExecuteRequest;
import io.pockethive.orchestrator.runtime.RuntimeCleanupContracts.ExecuteResponse;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeInventoryPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeRemovalPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.ComputeRuntimeResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitExchangeResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitQueueResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitTopologyPort;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RuntimeOwnershipManifestStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuntimeReconciliationServiceTest {

    @Test
    void activeSwarmUsesLifecycleCandidateAndBlocksDirectControllerDockerDeletion() {
        SwarmStore swarms = new SwarmStore();
        swarms.register(new Swarm("sw1", "controller-1", "manager-container", "run-1"));
        FakeInventory inventory = new FakeInventory(List.of(container("manager-container", "manager", "swarm-controller", "controller-1", "exited")));
        FakeRabbit rabbit = new FakeRabbit();
        rabbit.queues.put("ph.sw1.final", new RabbitQueueResource("ph.sw1.final", 0, 0));
        FakeManifests manifests = new FakeManifests();
        manifests.save(manifest());

        RuntimeReconciliationService service = service(swarms, manifests, inventory, inventory, rabbit, mock(ContainerLifecycleManager.class));

        Plan plan = service.plan(new PlanRequest("DOCKER_SINGLE", "sw1", "run-1", false, true));

        assertThat(plan.candidates())
            .extracting("candidateId")
            .containsExactly("lifecycle:swarm:sw1");
        assertThat(plan.blocked())
            .extracting("candidateId")
            .contains("docker:container:manager-container", "rabbit:queue:ph.sw1.final");
        assertThat(plan.candidateSetHash()).startsWith("sha256:");
    }

    @Test
    void orphanedSwarmPlanIncludesControllerContainerAndManifestRabbitResources() {
        FakeInventory inventory = new FakeInventory(List.of(container("manager-container", "manager", "swarm-controller", "controller-1", "exited")));
        FakeRabbit rabbit = new FakeRabbit();
        rabbit.queues.put("ph.control.sw1.swarm-controller.controller-1",
            new RabbitQueueResource("ph.control.sw1.swarm-controller.controller-1", 0, 0));
        rabbit.queues.put("ph.sw1.final", new RabbitQueueResource("ph.sw1.final", 12, 0));
        rabbit.exchanges.put("ph.sw1.hive", new RabbitExchangeResource("ph.sw1.hive"));
        FakeManifests manifests = new FakeManifests();
        manifests.save(manifest());

        RuntimeReconciliationService service = service(new SwarmStore(), manifests, inventory, inventory, rabbit, mock(ContainerLifecycleManager.class));

        Plan plan = service.plan(new PlanRequest("DOCKER_SINGLE", "sw1", "run-1", false, true));

        assertThat(plan.candidates())
            .extracting("candidateId")
            .containsExactly(
                "docker:container:manager-container",
                "rabbit:exchange:ph.sw1.hive",
                "rabbit:queue:ph.control.sw1.swarm-controller.controller-1",
                "rabbit:queue:ph.sw1.final");
        assertThat(plan.executionRisk()).isEqualTo("high");
        assertThat(plan.candidates().stream()
            .filter(candidate -> candidate.candidateId().equals("rabbit:queue:ph.sw1.final"))
            .findFirst().orElseThrow().highRisk()).isTrue();
    }

    @Test
    void oldRunControllerCanBeDockerCleanedWhenSameSwarmIdHasDifferentActiveRun() {
        SwarmStore swarms = new SwarmStore();
        swarms.register(new Swarm("sw1", "controller-2", "manager-container-new", "run-2"));
        FakeInventory inventory = new FakeInventory(List.of(
            container("manager-container-old", "manager", "swarm-controller", "controller-1", "exited", "sw1", "run-1")));
        FakeRabbit rabbit = new FakeRabbit();
        rabbit.queues.put("ph.control.sw1.swarm-controller.controller-1",
            new RabbitQueueResource("ph.control.sw1.swarm-controller.controller-1", 0, 0));
        rabbit.queues.put("ph.sw1.final", new RabbitQueueResource("ph.sw1.final", 0, 0));
        rabbit.exchanges.put("ph.sw1.hive", new RabbitExchangeResource("ph.sw1.hive"));
        FakeManifests manifests = new FakeManifests();
        manifests.save(manifest());

        RuntimeReconciliationService service = service(swarms, manifests, inventory, inventory, rabbit, mock(ContainerLifecycleManager.class));

        Plan plan = service.plan(new PlanRequest("DOCKER_SINGLE", "sw1", "run-1", false, true));

        assertThat(plan.candidates())
            .extracting("candidateId")
            .containsExactly(
                "docker:container:manager-container-old",
                "rabbit:queue:ph.control.sw1.swarm-controller.controller-1");
        assertThat(plan.blocked())
            .extracting("candidateId")
            .containsExactly(
                "rabbit:exchange:ph.sw1.hive",
                "rabbit:queue:ph.sw1.final");
    }

    @Test
    void planIncludesLiveWorkerControlQueuesDerivedFromWorkerLabels() {
        SwarmStore swarms = new SwarmStore();
        swarms.register(new Swarm("sw1", "controller-1", "manager-container", "run-1"));
        FakeInventory inventory = new FakeInventory(List.of(
            container("worker-container", "worker", "processor", "processor-1", "running")));
        FakeRabbit rabbit = new FakeRabbit();
        rabbit.queues.put("ph.control.sw1.processor.processor-1",
            new RabbitQueueResource("ph.control.sw1.processor.processor-1", 0, 1));
        FakeManifests manifests = new FakeManifests();
        manifests.save(manifest());

        RuntimeReconciliationService service = service(swarms, manifests, inventory, inventory, rabbit, mock(ContainerLifecycleManager.class));

        Plan plan = service.plan(new PlanRequest("DOCKER_SINGLE", "sw1", "run-1", true, true));

        assertThat(plan.candidates())
            .extracting("candidateId")
            .contains(
                "docker:container:worker-container",
                "rabbit:queue:ph.control.sw1.processor.processor-1");
        assertThat(plan.candidates().stream()
            .filter(candidate -> candidate.candidateId().equals("rabbit:queue:ph.control.sw1.processor.processor-1"))
            .findFirst().orElseThrow().highRisk()).isTrue();
    }

    @Test
    void includeRabbitFalseSuppressesRabbitCandidatesAndBlocks() {
        FakeInventory inventory = new FakeInventory(List.of());
        FakeRabbit rabbit = new FakeRabbit();
        rabbit.queues.put("ph.control.sw1.swarm-controller.controller-1",
            new RabbitQueueResource("ph.control.sw1.swarm-controller.controller-1", 0, 0));
        rabbit.queues.put("ph.sw1.final", new RabbitQueueResource("ph.sw1.final", 10, 1));
        rabbit.exchanges.put("ph.sw1.hive", new RabbitExchangeResource("ph.sw1.hive"));
        FakeManifests manifests = new FakeManifests();
        manifests.save(manifest());

        RuntimeReconciliationService service = service(new SwarmStore(), manifests, inventory, inventory, rabbit, mock(ContainerLifecycleManager.class));

        Plan plan = service.plan(new PlanRequest("DOCKER_SINGLE", "sw1", "run-1", false, false));

        assertThat(plan.includeRabbit()).isFalse();
        assertThat(plan.candidates()).isEmpty();
        assertThat(plan.blocked()).isEmpty();
    }

    @Test
    void missingManifestBlocksRabbitCleanupInsteadOfGuessingQueueNames() {
        FakeInventory inventory = new FakeInventory(List.of(container("worker-1", "worker", "processor", "processor-1", "exited")));
        RuntimeReconciliationService service = service(
            new SwarmStore(),
            new FakeManifests(),
            inventory,
            inventory,
            new FakeRabbit(),
            mock(ContainerLifecycleManager.class));

        Plan plan = service.plan(new PlanRequest("DOCKER_SINGLE", "sw1", "run-1", false, true));

        assertThat(plan.candidates()).extracting("candidateId").containsExactly("docker:container:worker-1");
        assertThat(plan.blocked()).extracting("candidateId").containsExactly("rabbit:manifest:sw1");
    }

    @Test
    void unmanagedAndIncompleteResourcesAreBlockedNotCandidates() {
        FakeInventory inventory = new FakeInventory(List.of(
            new ComputeRuntimeResource("foreign", "container", "foreign", "processor:test", "exited", Map.of()),
            new ComputeRuntimeResource("same-swarm-unmanaged", "container", "same-swarm-unmanaged", "processor:test", "exited", Map.of(
                "pockethive.swarmId", "sw1")),
            new ComputeRuntimeResource("partial", "container", "partial", "processor:test", "exited", Map.of(
                "pockethive.managed", "true",
                "pockethive.swarmId", "sw1"))));
        RuntimeReconciliationService service = service(
            new SwarmStore(),
            new FakeManifests(),
            inventory,
            inventory,
            new FakeRabbit(),
            mock(ContainerLifecycleManager.class));

        Plan plan = service.plan(new PlanRequest("DOCKER_SINGLE", "sw1", "run-1", false, false));

        assertThat(plan.candidates()).isEmpty();
        assertThat(plan.blocked())
            .extracting("candidateId")
            .containsExactly("docker:container:partial", "docker:container:same-swarm-unmanaged");
        assertThat(plan.blocked())
            .extracting("reason")
            .containsExactly(
                "missing required labels: pockethive.instance, pockethive.resourceKind, pockethive.role, pockethive.runId",
                "missing pockethive.managed=true");
    }

    @Test
    void pausedContainerRequiresRunningScopeAndIsHighRisk() {
        FakeInventory inventory = new FakeInventory(List.of(
            container("worker-paused", "worker", "processor", "processor-1", "paused")));
        RuntimeReconciliationService service = service(
            new SwarmStore(),
            new FakeManifests(),
            inventory,
            inventory,
            new FakeRabbit(),
            mock(ContainerLifecycleManager.class));

        Plan defaultPlan = service.plan(new PlanRequest("DOCKER_SINGLE", "sw1", "run-1", false, false));
        Plan runningPlan = service.plan(new PlanRequest("DOCKER_SINGLE", "sw1", "run-1", true, false));

        assertThat(defaultPlan.candidates()).isEmpty();
        assertThat(defaultPlan.blocked()).extracting("candidateId").containsExactly("docker:container:worker-paused");
        assertThat(runningPlan.candidates().get(0).highRisk()).isTrue();
    }

    @Test
    void executeRejectsHashMismatchBeforeMutating() {
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        FakeInventory inventory = new FakeInventory(List.of(container("worker-1", "worker", "processor", "processor-1", "exited")));
        RuntimeReconciliationService service = service(new SwarmStore(), new FakeManifests(), inventory, inventory, new FakeRabbit(), lifecycle);

        assertThatThrownBy(() -> service.execute(new ExecuteRequest(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            false,
            false,
            "sha256:wrong",
            List.of("docker:container:worker-1"),
            "idem-1",
            "remove stopped worker",
            "alice")))
            .isInstanceOf(RuntimeCleanupException.class)
            .hasMessageContaining("candidateSetHash");
        assertThat(inventory.removed).isEmpty();
        verifyNoInteractions(lifecycle);
    }

    @Test
    void executeIsIdempotentForSameKeyAndExactCleanupInput() {
        FakeInventory inventory = new FakeInventory(List.of(
            container("worker-1", "worker", "processor", "processor-1", "exited"),
            container("worker-2", "worker", "processor", "processor-2", "exited")));
        RuntimeReconciliationService service = service(
            new SwarmStore(),
            new FakeManifests(),
            inventory,
            inventory,
            new FakeRabbit(),
            mock(ContainerLifecycleManager.class));
        Plan plan = service.plan(new PlanRequest("DOCKER_SINGLE", "sw1", "run-1", false, false));

        ExecuteRequest request = new ExecuteRequest(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            false,
            false,
            plan.candidateSetHash(),
            List.of("docker:container:worker-1"),
            "idem-1",
            "remove stopped worker",
            "alice");

        ExecuteResponse first = service.execute(request);
        ExecuteResponse repeat = service.execute(request);

        assertThat(first.idempotent()).isFalse();
        assertThat(repeat.idempotent()).isTrue();
        assertThat(inventory.removed).containsExactly("container:worker-1");
        assertThatThrownBy(() -> service.execute(new ExecuteRequest(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            false,
            false,
            plan.candidateSetHash(),
            List.of("docker:container:worker-2"),
            "idem-1",
            "remove another stopped worker",
            "alice")))
            .isInstanceOf(RuntimeCleanupException.class)
            .hasMessageContaining("idempotencyKey");
    }

    @Test
    void executeRecordsFailedCandidateEvidence() {
        FakeInventory inventory = new FakeInventory(List.of(container("worker-1", "worker", "processor", "processor-1", "exited")));
        inventory.failRemoveIds.add("worker-1");
        RuntimeReconciliationService service = service(
            new SwarmStore(),
            new FakeManifests(),
            inventory,
            inventory,
            new FakeRabbit(),
            mock(ContainerLifecycleManager.class));
        Plan plan = service.plan(new PlanRequest("DOCKER_SINGLE", "sw1", "run-1", false, false));

        ExecuteResponse response = service.execute(new ExecuteRequest(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            false,
            false,
            plan.candidateSetHash(),
            List.of("docker:container:worker-1"),
            "idem-1",
            "remove stopped worker",
            "alice"));

        assertThat(response.evidence().resultByCandidate().get(0).status()).isEqualTo(RuntimeCleanupStatus.FAILED);
        assertThat(response.evidence().errors()).containsExactly("synthetic removal failure for worker-1");
    }

    @Test
    void executeLifecycleCandidateDeletesSwarmControllerViaLifecycleManager() {
        SwarmStore swarms = new SwarmStore();
        swarms.register(new Swarm("sw1", "controller-1", "manager-container", "run-1"));
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        FakeInventory inventory = new FakeInventory(List.of());
        RuntimeReconciliationService service = service(swarms, new FakeManifests(), inventory, inventory, new FakeRabbit(), lifecycle);
        Plan plan = service.plan(new PlanRequest("DOCKER_SINGLE", "sw1", "run-1", false, false));

        ExecuteResponse response = service.execute(new ExecuteRequest(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            false,
            false,
            plan.candidateSetHash(),
            List.of("lifecycle:swarm:sw1"),
            "idem-1",
            "remove stale controller",
            "alice"));

        assertThat(response.idempotent()).isFalse();
        assertThat(response.evidence().resultByCandidate().get(0).status()).isEqualTo(RuntimeCleanupStatus.REMOVED);
        verify(lifecycle).removeSwarm("sw1");
    }

    private static RuntimeReconciliationService service(SwarmStore swarms,
                                                        RuntimeOwnershipManifestStore manifests,
                                                        ComputeRuntimeInventoryPort inventory,
                                                        ComputeRuntimeRemovalPort removal,
                                                        RabbitTopologyPort rabbit,
                                                        ContainerLifecycleManager lifecycle) {
        return new RuntimeReconciliationService(
            swarms,
            manifests,
            inventory,
            removal,
            rabbit,
            new RuntimeCleanupEvidenceStore(),
            lifecycle,
            controlPlaneProperties());
    }

    private static ControlPlaneProperties controlPlaneProperties() {
        ControlPlaneProperties properties = new ControlPlaneProperties();
        properties.setExchange("ph.control");
        properties.setControlQueuePrefix("ph.control");
        properties.setSwarmId("sw1");
        properties.setInstanceId("orchestrator-1");
        return properties;
    }

    private static ComputeRuntimeResource container(String id, String kind, String role, String instance, String state) {
        return container(id, kind, role, instance, state, "sw1", "run-1");
    }

    private static ComputeRuntimeResource container(String id,
                                                    String kind,
                                                    String role,
                                                    String instance,
                                                    String state,
                                                    String swarmId,
                                                    String runId) {
        return new ComputeRuntimeResource(
            id,
            "container",
            id,
            role + ":test",
            state,
            Map.of(
                "pockethive.managed", "true",
                "pockethive.swarmId", swarmId,
                "pockethive.runId", runId,
                "pockethive.resourceKind", kind,
                "pockethive.role", role,
                "pockethive.instance", instance,
                "pockethive.image", role + ":test"));
    }

    private static RuntimeOwnershipManifest manifest() {
        return new RuntimeOwnershipManifest(
            "sw1",
            "run-1",
            "tpl-1",
            "DOCKER_SINGLE",
            Instant.parse("2026-01-01T00:00:00Z"),
            List.of(new RuntimeOwnershipManifest.RuntimeObject(
                "manager-container",
                "container",
                "manager",
                "swarm-controller",
                "controller-1",
                "controller:test")),
            new RuntimeOwnershipManifest.RabbitResources(
                List.of("ph.control.sw1.swarm-controller.controller-1"),
                List.of("ph.sw1.final"),
                List.of("ph.sw1.hive")));
    }

    private static final class FakeInventory implements ComputeRuntimeInventoryPort, ComputeRuntimeRemovalPort {
        private final List<ComputeRuntimeResource> resources;
        private final List<String> removed = new ArrayList<>();
        private final List<String> failRemoveIds = new ArrayList<>();

        private FakeInventory(List<ComputeRuntimeResource> resources) {
            this.resources = resources;
        }

        @Override
        public List<ComputeRuntimeResource> list(String computeAdapter) {
            return resources;
        }

        @Override
        public void removeContainer(String runtimeId) {
            if (failRemoveIds.contains(runtimeId)) {
                throw new IllegalStateException("synthetic removal failure for " + runtimeId);
            }
            removed.add("container:" + runtimeId);
        }

        @Override
        public void removeService(String runtimeId) {
            if (failRemoveIds.contains(runtimeId)) {
                throw new IllegalStateException("synthetic removal failure for " + runtimeId);
            }
            removed.add("service:" + runtimeId);
        }
    }

    private static final class FakeRabbit implements RabbitTopologyPort {
        private final Map<String, RabbitQueueResource> queues = new LinkedHashMap<>();
        private final Map<String, RabbitExchangeResource> exchanges = new LinkedHashMap<>();
        private final List<String> deleted = new ArrayList<>();

        @Override
        public Optional<RabbitQueueResource> queue(String name) {
            return Optional.ofNullable(queues.get(name));
        }

        @Override
        public Optional<RabbitExchangeResource> exchange(String name) {
            return Optional.ofNullable(exchanges.get(name));
        }

        @Override
        public void deleteQueue(String name) {
            deleted.add("queue:" + name);
        }

        @Override
        public void deleteExchange(String name) {
            deleted.add("exchange:" + name);
        }
    }

    private static final class FakeManifests implements RuntimeOwnershipManifestStore {
        private final Map<String, RuntimeOwnershipManifest> manifests = new LinkedHashMap<>();

        @Override
        public void save(RuntimeOwnershipManifest manifest) {
            manifests.put(manifest.swarmId() + "|" + manifest.runId(), manifest);
        }

        @Override
        public Optional<RuntimeOwnershipManifest> find(String swarmId, String runId) {
            return Optional.ofNullable(manifests.get(swarmId + "|" + runId));
        }

        @Override
        public Optional<RuntimeOwnershipManifest> findLatest(String swarmId) {
            return manifests.values().stream()
                .filter(manifest -> manifest.swarmId().equals(swarmId))
                .findFirst();
        }
    }
}
