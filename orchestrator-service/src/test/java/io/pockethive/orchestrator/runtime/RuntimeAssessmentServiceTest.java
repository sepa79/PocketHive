package io.pockethive.orchestrator.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.runtime.RuntimeAssessmentContracts.AssessmentCheck;
import io.pockethive.orchestrator.runtime.RuntimeAssessmentContracts.AssessmentRequest;
import io.pockethive.orchestrator.runtime.RuntimeAssessmentContracts.AssessmentState;
import io.pockethive.orchestrator.runtime.RuntimeAssessmentContracts.DifferenceKind;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.BlockedResource;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.Counts;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RabbitExchangeSnapshot;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RabbitQueueSnapshot;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RabbitTopologyRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RabbitTopologySnapshot;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.ResourceListRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.ResourceListResponse;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeEntry;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.SourceSummary;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.Health;
import io.pockethive.swarm.model.lifecycle.RuntimeResourceState;
import io.pockethive.swarm.model.lifecycle.RuntimeIntent;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import io.pockethive.swarm.model.lifecycle.WorkloadIntent;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class RuntimeAssessmentServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");
    private final SwarmStore swarms = new SwarmStore();
    private final RuntimeDebugService debug = mock(RuntimeDebugService.class);
    private final RuntimeReconciliationService reconciliation = mock(RuntimeReconciliationService.class);
    private RuntimeAssessmentService service;

    @BeforeEach
    void setUp() {
        service = new RuntimeAssessmentService(
            swarms, debug, reconciliation, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void returnsConsistentAssessmentWithCompatibilityProjections() {
        Swarm swarm = observedSwarm();
        swarms.register(swarm);
        when(debug.list(any(ResourceListRequest.class))).thenReturn(resources(
            List.of(workerRuntime()), List.of(managerRuntime()), List.of()));
        when(reconciliation.ownershipManifest(any(RabbitTopologyRequest.class))).thenReturn(manifest());
        when(reconciliation.rabbitTopology(any(RabbitTopologyRequest.class))).thenReturn(rabbit(true));

        var result = service.assess(new AssessmentRequest("sw1", "run-1"));

        assertThat(result.assessmentContractVersion()).isEqualTo("1");
        assertThat(result.overall()).isEqualTo(AssessmentState.CONSISTENT);
        assertThat(result.assessedAt()).isEqualTo(NOW);
        assertThat(result.checks()).extracting("check").containsExactly(
            AssessmentCheck.REGISTRY,
            AssessmentCheck.CONTROL_PLANE,
            AssessmentCheck.OWNERSHIP_MANIFEST,
            AssessmentCheck.RUNTIME_INVENTORY,
            AssessmentCheck.RABBIT_TOPOLOGY);
        assertThat(result.swarm().id()).isEqualTo("sw1");
        assertThat(result.resources().workers()).hasSize(1);
        assertThat(result.manifest().runId()).isEqualTo("run-1");
        assertThat(result.rabbitTopology().queues()).hasSize(1);
    }

    @Test
    void reportsTypedDriftForContradictoryRuntimeAndRabbitEvidence() {
        swarms.register(observedSwarm());
        when(debug.list(any(ResourceListRequest.class))).thenReturn(resources(
            List.of(), List.of(), List.of()));
        when(reconciliation.ownershipManifest(any(RabbitTopologyRequest.class))).thenReturn(manifest());
        when(reconciliation.rabbitTopology(any(RabbitTopologyRequest.class))).thenReturn(rabbit(false));

        var result = service.assess(new AssessmentRequest("sw1", null));

        assertThat(result.overall()).isEqualTo(AssessmentState.DRIFTED);
        assertThat(result.checks()).filteredOn(check -> check.check() == AssessmentCheck.RUNTIME_INVENTORY)
            .singleElement()
            .satisfies(check -> assertThat(check.differences()).extracting("kind")
                .contains(DifferenceKind.MISSING_RUNTIME));
        assertThat(result.checks()).filteredOn(check -> check.check() == AssessmentCheck.RABBIT_TOPOLOGY)
            .singleElement()
            .extracting("state").isEqualTo(AssessmentState.DRIFTED);
    }

    @Test
    void reportsControlPlaneDriftWhenObservedWorkloadContradictsIntent() {
        Swarm swarm = observedSwarm();
        swarm.requestWorkload(WorkloadIntent.RUNNING);
        swarm.updateObservation(
            ControllerState.READY,
            WorkloadState.STOPPED,
            Health.HEALTHY,
            RuntimeResourceState.PRESENT,
            Map.of("workers", List.of(Map.of("role", "generator", "instance", "generator-1"))),
            NOW);
        swarms.register(swarm);
        when(debug.list(any(ResourceListRequest.class))).thenReturn(resources(
            List.of(workerRuntime()), List.of(managerRuntime()), List.of()));
        when(reconciliation.ownershipManifest(any(RabbitTopologyRequest.class))).thenReturn(manifest());
        when(reconciliation.rabbitTopology(any(RabbitTopologyRequest.class))).thenReturn(rabbit(true));

        var result = service.assess(new AssessmentRequest("sw1", "run-1"));

        assertThat(result.overall()).isEqualTo(AssessmentState.DRIFTED);
        assertThat(result.checks()).filteredOn(check -> check.check() == AssessmentCheck.CONTROL_PLANE)
            .singleElement()
            .satisfies(check -> {
                assertThat(check.differences()).extracting("kind")
                    .containsExactly(DifferenceKind.CONTROL_PLANE_STATE_MISMATCH);
                assertThat(check.differences().getFirst().expected())
                    .isEqualTo(Map.of(
                        "controllerState", ControllerState.READY,
                        "health", Health.HEALTHY,
                        "runtimeResourceState", RuntimeResourceState.PRESENT,
                        "workloadState", WorkloadState.RUNNING));
            });
    }

    @Test
    void reportsControlPlaneDriftForBothRuntimeIntentContradictions() {
        Swarm expectedPresent = observedSwarm("sw-present", "run-present");
        expectedPresent.updateObservation(
            ControllerState.READY,
            WorkloadState.RUNNING,
            Health.HEALTHY,
            RuntimeResourceState.ABSENT,
            workerObservation("generator:latest"),
            NOW);
        swarms.register(expectedPresent);

        Swarm expectedAbsent = observedSwarm("sw-absent", "run-absent");
        expectedAbsent.requestRuntime(RuntimeIntent.ABSENT);
        expectedAbsent.updateObservation(
            ControllerState.READY,
            WorkloadState.STOPPED,
            Health.HEALTHY,
            RuntimeResourceState.PRESENT,
            workerObservation("generator:latest"),
            NOW);
        swarms.register(expectedAbsent);
        stubOwnerEvidence();

        assertControlPlaneDrift(service.assess(new AssessmentRequest("sw-present", "run-present")),
            RuntimeResourceState.PRESENT, RuntimeResourceState.ABSENT);
        assertControlPlaneDrift(service.assess(new AssessmentRequest("sw-absent", "run-absent")),
            RuntimeResourceState.ABSENT, RuntimeResourceState.PRESENT);
    }

    @Test
    void reportsControlPlaneDriftWhenStoppedIntentContradictsRunningObservation() {
        Swarm swarm = observedSwarm();
        swarm.requestWorkload(WorkloadIntent.STOPPED);
        swarms.register(swarm);
        stubOwnerEvidence();

        var result = service.assess(new AssessmentRequest("sw1", "run-1"));

        assertThat(controlPlane(result).state()).isEqualTo(AssessmentState.DRIFTED);
        assertThat(controlPlane(result).differences()).extracting("kind")
            .containsExactly(DifferenceKind.CONTROL_PLANE_STATE_MISMATCH);
    }

    @Test
    void reportsTransitionalControlPlaneStateAsIncompleteWithCurrentEvidence() {
        Swarm swarm = observedSwarm();
        swarm.updateObservation(
            ControllerState.PROVISIONING,
            WorkloadState.STOPPED,
            Health.HEALTHY,
            RuntimeResourceState.PRESENT,
            workerObservation("generator:latest"),
            NOW);
        swarms.register(swarm);
        stubOwnerEvidence();

        var result = service.assess(new AssessmentRequest("sw1", "run-1"));

        assertThat(controlPlane(result).state()).isEqualTo(AssessmentState.INCOMPLETE);
        assertThat(controlPlane(result).differences()).singleElement().satisfies(difference -> {
            assertThat(difference.kind()).isEqualTo(DifferenceKind.SOURCE_UNAVAILABLE);
            assertThat(difference.actual()).isEqualTo(Map.of(
                "controllerState", ControllerState.PROVISIONING,
                "health", Health.HEALTHY,
                "runtimeResourceState", RuntimeResourceState.PRESENT,
                "workloadState", WorkloadState.STOPPED));
        });
    }

    @Test
    void reportsObservedWorkerImageDriftAndAnUnexpectedRuntime() {
        swarms.register(observedSwarm());
        RuntimeEntry wrongImage = workerRuntime("worker-runtime-1", "generator", "generator-1", "other:latest");
        RuntimeEntry extra = workerRuntime("worker-runtime-2", "processor", "processor-1", "processor:latest");
        when(debug.list(any(ResourceListRequest.class))).thenReturn(resources(
            List.of(wrongImage, extra), List.of(managerRuntime()), List.of()));
        when(reconciliation.ownershipManifest(any(RabbitTopologyRequest.class))).thenReturn(manifest());
        when(reconciliation.rabbitTopology(any(RabbitTopologyRequest.class))).thenReturn(rabbit(true));

        var result = service.assess(new AssessmentRequest("sw1", "run-1"));

        assertThat(result.checks()).filteredOn(check -> check.check() == AssessmentCheck.RUNTIME_INVENTORY)
            .singleElement()
            .satisfies(check -> {
                assertThat(check.state()).isEqualTo(AssessmentState.DRIFTED);
                assertThat(check.differences()).extracting("kind")
                    .containsExactlyInAnyOrder(DifferenceKind.IMAGE_MISMATCH, DifferenceKind.UNEXPECTED_RUNTIME);
            });
    }

    @Test
    void reportsIncompleteWhenWorkerObservationCannotIdentifyEveryWorker() {
        Swarm swarm = observedSwarm();
        swarm.updateObservation(
            ControllerState.READY,
            WorkloadState.RUNNING,
            Health.HEALTHY,
            RuntimeResourceState.PRESENT,
            Map.of("workers", List.of(Map.of("role", "generator"), "invalid-worker")),
            NOW);
        swarms.register(swarm);
        when(debug.list(any(ResourceListRequest.class))).thenReturn(resources(
            List.of(), List.of(managerRuntime()), List.of()));
        when(reconciliation.ownershipManifest(any(RabbitTopologyRequest.class))).thenReturn(manifest());
        when(reconciliation.rabbitTopology(any(RabbitTopologyRequest.class))).thenReturn(rabbit(true));

        var result = service.assess(new AssessmentRequest("sw1", "run-1"));

        assertThat(result.checks()).filteredOn(check -> check.check() == AssessmentCheck.RUNTIME_INVENTORY)
            .singleElement()
            .satisfies(check -> {
                assertThat(check.state()).isEqualTo(AssessmentState.INCOMPLETE);
                assertThat(check.differences()).extracting("kind")
                    .containsOnly(DifferenceKind.SOURCE_UNAVAILABLE);
            });
    }

    @Test
    void reportsIncompleteWhenCanonicalWorkerObservationIsMissing() {
        Swarm swarm = observedSwarm();
        swarm.updateObservation(
            ControllerState.READY,
            WorkloadState.RUNNING,
            Health.HEALTHY,
            RuntimeResourceState.PRESENT,
            Map.of("status", "available"),
            NOW);
        swarms.register(swarm);
        stubOwnerEvidence();

        var result = service.assess(new AssessmentRequest("sw1", "run-1"));

        assertThat(result.checks()).filteredOn(check -> check.check() == AssessmentCheck.RUNTIME_INVENTORY)
            .singleElement()
            .satisfies(check -> {
                assertThat(check.state()).isEqualTo(AssessmentState.INCOMPLETE);
                assertThat(check.differences()).extracting("kind")
                    .containsExactly(DifferenceKind.SOURCE_UNAVAILABLE, DifferenceKind.UNEXPECTED_RUNTIME);
            });
    }

    @Test
    void treatsAnUnreportedWorkerImageAsUnknownRatherThanDrift() {
        Swarm swarm = observedSwarm();
        swarm.updateObservation(
            ControllerState.READY,
            WorkloadState.RUNNING,
            Health.HEALTHY,
            RuntimeResourceState.PRESENT,
            workerObservationWithoutRuntime(),
            NOW);
        swarms.register(swarm);
        stubOwnerEvidence();

        var result = service.assess(new AssessmentRequest("sw1", "run-1"));

        assertThat(result.overall()).isEqualTo(AssessmentState.CONSISTENT);
        assertThat(result.checks()).filteredOn(check -> check.check() == AssessmentCheck.RUNTIME_INVENTORY)
            .singleElement()
            .satisfies(check -> assertThat(check.differences()).isEmpty());
    }

    @Test
    void reportsIncompleteWhenOwnerSourcesCannotSupportAConclusion() {
        swarms.register(new Swarm("sw1", "controller-1", "manager-1", "run-1", NetworkMode.DIRECT));
        when(debug.list(any(ResourceListRequest.class))).thenReturn(resources(
            List.of(),
            List.of(),
            List.of(new BlockedResource(
                "bad-1", "container", "bad", "unknown", "missing labels", Map.of()))));
        when(reconciliation.ownershipManifest(any(RabbitTopologyRequest.class))).thenThrow(
            new RuntimeCleanupException(HttpStatus.NOT_FOUND, "runtime ownership manifest was not found"));
        when(reconciliation.rabbitTopology(any(RabbitTopologyRequest.class))).thenReturn(new RabbitTopologySnapshot(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            SourceSummary.missing("runtime ownership manifest was not found"),
            SourceSummary.present(),
            true,
            List.of(),
            List.of(),
            List.of()));

        var result = service.assess(new AssessmentRequest("sw1", "run-1"));

        assertThat(result.overall()).isEqualTo(AssessmentState.INCOMPLETE);
        assertThat(result.manifest()).isNull();
        assertThat(result.checks()).filteredOn(check -> check.state() == AssessmentState.INCOMPLETE)
            .extracting("check")
            .contains(
                AssessmentCheck.CONTROL_PLANE,
                AssessmentCheck.OWNERSHIP_MANIFEST,
                AssessmentCheck.RUNTIME_INVENTORY,
                AssessmentCheck.RABBIT_TOPOLOGY);
    }

    @Test
    void requiresAnExactRegisteredSwarmRun() {
        swarms.register(observedSwarm());

        assertThatThrownBy(() -> service.assess(null))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessage("assessment request is required");
        assertThatThrownBy(() -> service.assess(new AssessmentRequest(" sw1", null)))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessage("swarmId must be non-blank and normalized");
        assertThatThrownBy(() -> service.assess(new AssessmentRequest("sw1", " run-1")))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessage("runId must be non-blank and normalized");
        assertThatThrownBy(() -> service.assess(new AssessmentRequest("sw1", "other-run")))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessage("swarm run was not found");
        assertThatThrownBy(() -> service.assess(new AssessmentRequest("missing", null)))
            .isInstanceOf(RuntimeDebugException.class)
            .hasMessage("swarm was not found");
    }

    @Test
    void publicConstructorUsesTheProductionClockPath() {
        assertThat(new RuntimeAssessmentService(swarms, debug, reconciliation)).isNotNull();
    }

    @Test
    void propagatesOwnerFailuresOtherThanAnAbsentManifest() {
        swarms.register(observedSwarm());
        when(debug.list(any(ResourceListRequest.class))).thenReturn(resources(
            List.of(workerRuntime()), List.of(managerRuntime()), List.of()));
        RuntimeCleanupException failure = new RuntimeCleanupException(
            HttpStatus.SERVICE_UNAVAILABLE, "ownership source failed");
        when(reconciliation.ownershipManifest(any(RabbitTopologyRequest.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.assess(new AssessmentRequest("sw1", "run-1")))
            .isSameAs(failure);
    }

    @Test
    void reportsDuplicateExpectedActualAndObservedWorkerIdentities() {
        Swarm swarm = observedSwarm();
        swarm.updateObservation(
            ControllerState.READY,
            WorkloadState.RUNNING,
            Health.HEALTHY,
            RuntimeResourceState.PRESENT,
            Map.of("workers", List.of(
                workerObservationEntry("generator:latest"),
                workerObservationEntry("generator:latest"))),
            NOW);
        swarms.register(swarm);
        RuntimeEntry duplicateWorker = workerRuntime(
            "worker-runtime-duplicate", "generator", "generator-1", "generator:latest");
        when(debug.list(any(ResourceListRequest.class))).thenReturn(resources(
            List.of(workerRuntime(), duplicateWorker), List.of(managerRuntime()), List.of()));
        when(reconciliation.ownershipManifest(any(RabbitTopologyRequest.class))).thenReturn(
            manifest(List.of(managerObject("manager-1"), managerObject("manager-duplicate"))));
        when(reconciliation.rabbitTopology(any(RabbitTopologyRequest.class))).thenReturn(rabbit(true));

        var result = service.assess(new AssessmentRequest("sw1", "run-1"));

        assertThat(result.checks()).filteredOn(check -> check.check() == AssessmentCheck.RUNTIME_INVENTORY)
            .singleElement()
            .satisfies(check -> assertThat(check.differences()).extracting("kind")
                .containsOnly(DifferenceKind.DUPLICATE_RUNTIME));
    }

    @Test
    void reportsManagerRuntimeIdentityMismatch() {
        swarms.register(observedSwarm());
        RuntimeEntry managerWithWrongRuntimeId = new RuntimeEntry(
            "other-manager", "container", "manager", "manager", "sw1", "run-1",
            "swarm-controller", "controller-1", "controller-1", "running", true,
            "swarm-controller:latest", "latest", null, null, "latest", null,
            null, null, "registered", Map.of());
        when(debug.list(any(ResourceListRequest.class))).thenReturn(resources(
            List.of(workerRuntime()), List.of(managerWithWrongRuntimeId), List.of()));
        when(reconciliation.ownershipManifest(any(RabbitTopologyRequest.class))).thenReturn(manifest());
        when(reconciliation.rabbitTopology(any(RabbitTopologyRequest.class))).thenReturn(rabbit(true));

        var result = service.assess(new AssessmentRequest("sw1", "run-1"));

        assertThat(result.checks()).filteredOn(check -> check.check() == AssessmentCheck.RUNTIME_INVENTORY)
            .singleElement()
            .satisfies(check -> assertThat(check.differences()).extracting("kind")
                .containsExactly(DifferenceKind.RUNTIME_ID_MISMATCH));
    }

    private static Swarm observedSwarm() {
        return observedSwarm("sw1", "run-1");
    }

    private static Swarm observedSwarm(String swarmId, String runId) {
        Swarm swarm = new Swarm(swarmId, "controller-1", "manager-1", runId, NetworkMode.DIRECT);
        swarm.requestWorkload(WorkloadIntent.RUNNING);
        swarm.updateObservation(
            ControllerState.READY,
            WorkloadState.RUNNING,
            Health.HEALTHY,
            RuntimeResourceState.PRESENT,
            workerObservation("generator:latest"),
            NOW);
        return swarm;
    }

    private void stubOwnerEvidence() {
        when(debug.list(any(ResourceListRequest.class))).thenReturn(resources(
            List.of(workerRuntime()), List.of(managerRuntime()), List.of()));
        when(reconciliation.ownershipManifest(any(RabbitTopologyRequest.class))).thenReturn(manifest());
        when(reconciliation.rabbitTopology(any(RabbitTopologyRequest.class))).thenReturn(rabbit(true));
    }

    private static RuntimeAssessmentContracts.CheckResult controlPlane(
        RuntimeAssessmentContracts.AssessmentResponse response) {
        return response.checks().stream()
            .filter(check -> check.check() == AssessmentCheck.CONTROL_PLANE)
            .findFirst()
            .orElseThrow();
    }

    private static void assertControlPlaneDrift(
        RuntimeAssessmentContracts.AssessmentResponse response,
        RuntimeResourceState expectedRuntime,
        RuntimeResourceState actualRuntime) {
        assertThat(controlPlane(response).state()).isEqualTo(AssessmentState.DRIFTED);
        assertThat(controlPlane(response).differences()).singleElement().satisfies(difference -> {
            assertThat(difference.kind()).isEqualTo(DifferenceKind.CONTROL_PLANE_STATE_MISMATCH);
            assertThat(difference.expected()).isInstanceOfSatisfying(Map.class,
                expected -> assertThat(expected).containsEntry("runtimeResourceState", expectedRuntime));
            assertThat(difference.actual()).isInstanceOfSatisfying(Map.class,
                actual -> assertThat(actual).containsEntry("runtimeResourceState", actualRuntime));
        });
    }

    private static Map<String, Object> workerObservation(String image) {
        return Map.of("workers", List.of(workerObservationEntry(image)));
    }

    private static Map<String, Object> workerObservationEntry(String image) {
        return Map.of(
            "role", "generator",
            "instance", "generator-1",
            "runtime", Map.of(
                "containerId", "worker-runtime-1",
                "image", image));
    }

    private static Map<String, Object> workerObservationWithoutRuntime() {
        return Map.of("workers", List.of(Map.of(
            "role", "generator",
            "instance", "generator-1")));
    }

    private static RuntimeEntry workerRuntime() {
        return workerRuntime("worker-runtime-1", "generator", "generator-1", "generator:latest");
    }

    private static RuntimeEntry workerRuntime(
        String runtimeId,
        String role,
        String instance,
        String image) {
        return new RuntimeEntry(
            runtimeId, "container", "worker", "worker", "sw1", "run-1",
            role, instance, instance, "running", true,
            image, "latest", null, null, "latest", null,
            null, null, "registered", Map.of());
    }

    private static RuntimeEntry managerRuntime() {
        return new RuntimeEntry(
            "manager-1", "container", "manager", "manager", "sw1", "run-1",
            "swarm-controller", "controller-1", "controller-1", "running", true,
            "swarm-controller:latest", "latest", null, null, "latest", null,
            null, null, "registered", Map.of());
    }

    private static ResourceListResponse resources(
        List<RuntimeEntry> workers,
        List<RuntimeEntry> managers,
        List<BlockedResource> blocked) {
        return new ResourceListResponse(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            new Counts(workers.size(), managers.size(), blocked.size()),
            workers,
            managers,
            blocked);
    }

    private static RuntimeOwnershipManifest manifest() {
        return manifest(List.of(managerObject("manager-1")));
    }

    private static RuntimeOwnershipManifest manifest(
        List<RuntimeOwnershipManifest.RuntimeObject> runtimeObjects) {
        return new RuntimeOwnershipManifest(
            "sw1",
            "run-1",
            "template-1",
            "DOCKER_SINGLE",
            NOW,
            runtimeObjects,
            new RuntimeOwnershipManifest.RabbitResources(
                List.of("ph.control.sw1.generator.generator-1"),
                List.of(),
                List.of("ph.control")));
    }

    private static RuntimeOwnershipManifest.RuntimeObject managerObject(String runtimeId) {
        return new RuntimeOwnershipManifest.RuntimeObject(
            runtimeId, "container", "manager", "swarm-controller", "controller-1",
            "swarm-controller:latest");
    }

    private static RabbitTopologySnapshot rabbit(boolean present) {
        return new RabbitTopologySnapshot(
            "DOCKER_SINGLE",
            "sw1",
            "run-1",
            SourceSummary.present(),
            SourceSummary.present(),
            true,
            List.of(new RabbitQueueSnapshot(
                "ph.control.sw1.generator.generator-1",
                present,
                0L,
                1,
                "running",
                true,
                false,
                false,
                null)),
            List.of(new RabbitExchangeSnapshot(
                "ph.control", present, "topic", true, false, null)),
            List.of());
    }
}
