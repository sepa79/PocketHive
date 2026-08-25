package io.pockethive.orchestrator.runtime;

import io.pockethive.docker.compute.PocketHiveDockerLabels;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.orchestrator.runtime.RuntimeAssessmentContracts.AssessmentCheck;
import io.pockethive.orchestrator.runtime.RuntimeAssessmentContracts.AssessmentRequest;
import io.pockethive.orchestrator.runtime.RuntimeAssessmentContracts.AssessmentResponse;
import io.pockethive.orchestrator.runtime.RuntimeAssessmentContracts.AssessmentState;
import io.pockethive.orchestrator.runtime.RuntimeAssessmentContracts.CheckResult;
import io.pockethive.orchestrator.runtime.RuntimeAssessmentContracts.Difference;
import io.pockethive.orchestrator.runtime.RuntimeAssessmentContracts.DifferenceKind;
import io.pockethive.orchestrator.runtime.RuntimeAssessmentContracts.SwarmSnapshot;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RabbitExchangeSnapshot;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RabbitQueueSnapshot;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RabbitTopologyRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RabbitTopologySnapshot;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.ResourceListRequest;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.ResourceListResponse;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RuntimeEntry;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.Health;
import io.pockethive.swarm.model.lifecycle.RuntimeResourceState;
import io.pockethive.swarm.model.lifecycle.RuntimeIntent;
import io.pockethive.swarm.model.lifecycle.WorkloadIntent;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public final class RuntimeAssessmentService {
    private static final String OBSERVATION_WORKERS = "workers";
    private static final String OBSERVATION_ROLE = "role";
    private static final String OBSERVATION_INSTANCE = "instance";
    private static final String OBSERVATION_RUNTIME = "runtime";
    private static final String OBSERVATION_IMAGE = "image";
    private final SwarmStore swarms;
    private final RuntimeDebugService runtimeDebug;
    private final RuntimeReconciliationService reconciliation;
    private final Clock clock;

    @Autowired
    public RuntimeAssessmentService(
        SwarmStore swarms,
        RuntimeDebugService runtimeDebug,
        RuntimeReconciliationService reconciliation) {
        this(swarms, runtimeDebug, reconciliation, Clock.systemUTC());
    }

    RuntimeAssessmentService(
        SwarmStore swarms,
        RuntimeDebugService runtimeDebug,
        RuntimeReconciliationService reconciliation,
        Clock clock) {
        this.swarms = Objects.requireNonNull(swarms, "swarms");
        this.runtimeDebug = Objects.requireNonNull(runtimeDebug, "runtimeDebug");
        this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AssessmentResponse assess(AssessmentRequest request) {
        if (request == null) {
            throw error(HttpStatus.BAD_REQUEST, "assessment request is required");
        }
        String swarmId = requireText(request.swarmId(), "swarmId");
        String requestedRunId = optionalText(request.runId(), "runId");
        Swarm swarm = swarms.find(swarmId)
            .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "swarm was not found"));
        if (requestedRunId != null && !requestedRunId.equals(swarm.getRunId())) {
            throw error(HttpStatus.NOT_FOUND, "swarm run was not found");
        }
        String runId = swarm.getRunId();
        ResourceListResponse resources = runtimeDebug.list(new ResourceListRequest(swarmId, runId, true));
        RuntimeOwnershipManifest manifest = ownershipManifest(swarmId, runId);
        RabbitTopologySnapshot rabbit = reconciliation.rabbitTopology(new RabbitTopologyRequest(swarmId, runId));

        List<CheckResult> checks = List.of(
            registryCheck(),
            controlPlaneCheck(swarm),
            manifestCheck(manifest),
            inventoryCheck(resources, manifest, swarm),
            rabbitCheck(rabbit));
        return new AssessmentResponse(
            RuntimeDebugContracts.RUNTIME_ASSESSMENT_CONTRACT_VERSION,
            overall(checks),
            swarmId,
            runId,
            clock.instant(),
            checks,
            snapshot(swarm),
            resources,
            manifest,
            rabbit);
    }

    private RuntimeOwnershipManifest ownershipManifest(String swarmId, String runId) {
        try {
            return reconciliation.ownershipManifest(new RabbitTopologyRequest(swarmId, runId));
        } catch (RuntimeCleanupException exception) {
            if (exception.status() == HttpStatus.NOT_FOUND) {
                return null;
            }
            throw exception;
        }
    }

    private static CheckResult registryCheck() {
        return consistent(AssessmentCheck.REGISTRY,
            "The exact swarm and run are registered by Orchestrator.");
    }

    private static CheckResult controlPlaneCheck(Swarm swarm) {
        if (swarm.getObservation().isEmpty()
            || swarm.getControllerState() == ControllerState.UNKNOWN
            || swarm.getHealth() == Health.UNKNOWN
            || swarm.getRuntimeResourceState() == RuntimeResourceState.UNKNOWN
            || swarm.getWorkloadState() == WorkloadState.UNKNOWN
            || swarm.getWorkloadState() == WorkloadState.UNAVAILABLE) {
            return incomplete(AssessmentCheck.CONTROL_PLANE,
                "Current control-plane observation is unavailable.",
                DifferenceKind.SOURCE_UNAVAILABLE, "control-plane", swarm.getId(), "current observation", null);
        }
        if (swarm.getControllerState() == ControllerState.PROVISIONING
            || swarm.getRuntimeResourceState() == RuntimeResourceState.REMOVING
            || swarm.getWorkloadState() == WorkloadState.STARTING
            || swarm.getWorkloadState() == WorkloadState.STOPPING) {
            return incomplete(AssessmentCheck.CONTROL_PLANE,
                "Control-plane state is transitional and cannot support a final conclusion.",
                DifferenceKind.SOURCE_UNAVAILABLE, "control-plane", swarm.getId(),
                "stable observation", controlPlaneState(swarm));
        }
        boolean runtimeMismatch = (swarm.getRuntimeIntent() == RuntimeIntent.PRESENT
            && swarm.getRuntimeResourceState() == RuntimeResourceState.ABSENT)
            || (swarm.getRuntimeIntent() == RuntimeIntent.ABSENT
            && swarm.getRuntimeResourceState() == RuntimeResourceState.PRESENT);
        boolean workloadMismatch = (swarm.getWorkloadIntent() == WorkloadIntent.RUNNING
            && swarm.getWorkloadState() == WorkloadState.STOPPED)
            || (swarm.getWorkloadIntent() == WorkloadIntent.STOPPED
            && swarm.getWorkloadState() == WorkloadState.RUNNING);
        if (swarm.getControllerState() == ControllerState.FAILED
            || swarm.getHealth() == Health.FAILED
            || swarm.getHealth() == Health.DEGRADED
            || runtimeMismatch
            || workloadMismatch) {
            return drifted(AssessmentCheck.CONTROL_PLANE,
                "Control-plane state contradicts registered intent or expected health.",
                DifferenceKind.CONTROL_PLANE_STATE_MISMATCH, "swarm", swarm.getId(),
                Map.of(
                    "controllerState", ControllerState.READY,
                    "health", Health.HEALTHY,
                    "runtimeResourceState", expectedRuntimeState(swarm.getRuntimeIntent()),
                    "workloadState", expectedWorkloadState(swarm.getWorkloadIntent())),
                controlPlaneState(swarm));
        }
        return consistent(AssessmentCheck.CONTROL_PLANE,
            "Current control-plane observation is available and non-failed.");
    }

    private static CheckResult manifestCheck(RuntimeOwnershipManifest manifest) {
        if (manifest == null) {
            return incomplete(AssessmentCheck.OWNERSHIP_MANIFEST,
                "Runtime ownership manifest is unavailable.",
                DifferenceKind.SOURCE_UNAVAILABLE, "manifest", "runtime-ownership", "available", null);
        }
        return consistent(AssessmentCheck.OWNERSHIP_MANIFEST,
            "The exact runtime ownership manifest is available.");
    }

    private static CheckResult inventoryCheck(
        ResourceListResponse resources,
        RuntimeOwnershipManifest manifest,
        Swarm swarm) {
        List<Difference> differences = new ArrayList<>();
        for (var blocked : resources.blocked()) {
            differences.add(new Difference(
                DifferenceKind.BLOCKED_RUNTIME,
                blocked.runtimeType(),
                blocked.runtimeId(),
                "complete PocketHive ownership labels",
                blocked.reason()));
        }
        if (manifest == null) {
            differences.add(new Difference(
                DifferenceKind.SOURCE_UNAVAILABLE,
                "manifest",
                "runtime-ownership",
                "available",
                null));
            return new CheckResult(
                AssessmentCheck.RUNTIME_INVENTORY,
                AssessmentState.INCOMPLETE,
                "Runtime inventory cannot be compared without an ownership manifest.",
                differences);
        }

        Map<String, ExpectedRuntime> expected = new LinkedHashMap<>();
        for (RuntimeOwnershipManifest.RuntimeObject object : manifest.runtimeObjects()) {
            String identity = identity(object.resourceKind(), object.role(), object.instance());
            ExpectedRuntime expectedRuntime = ExpectedRuntime.fromManifest(object);
            ExpectedRuntime previous = expected.putIfAbsent(identity, expectedRuntime);
            if (previous != null) {
                differences.add(new Difference(
                    DifferenceKind.DUPLICATE_RUNTIME,
                    object.resourceKind(),
                    identity,
                    previous.evidence(),
                    expectedRuntime.evidence()));
            }
        }
        boolean workerObservationComplete = appendExpectedWorkers(swarm, expected, differences);
        Map<String, RuntimeEntry> actual = new LinkedHashMap<>();
        for (RuntimeEntry entry : concat(resources.workers(), resources.managers())) {
            String identity = identity(entry.resourceKind(), entry.role(), entry.instance());
            RuntimeEntry previous = actual.putIfAbsent(identity, entry);
            if (previous != null) {
                differences.add(new Difference(
                    DifferenceKind.DUPLICATE_RUNTIME,
                    entry.resourceKind(),
                    identity,
                    previous,
                    entry));
            }
        }
        expected.forEach((identity, expectedRuntime) -> {
            RuntimeEntry entry = actual.remove(identity);
            if (entry == null) {
                differences.add(new Difference(
                    DifferenceKind.MISSING_RUNTIME,
                    expectedRuntime.resourceKind(),
                    identity,
                    expectedRuntime.evidence(),
                    null));
                return;
            }
            if (expectedRuntime.runtimeId() != null
                && !Objects.equals(expectedRuntime.runtimeId(), entry.runtimeId())) {
                differences.add(new Difference(
                    DifferenceKind.RUNTIME_ID_MISMATCH,
                    expectedRuntime.resourceKind(),
                    identity,
                    expectedRuntime.runtimeId(),
                    entry.runtimeId()));
            }
            if (expectedRuntime.image() != null
                && !Objects.equals(expectedRuntime.image(), entry.image())) {
                differences.add(new Difference(
                    DifferenceKind.IMAGE_MISMATCH,
                    expectedRuntime.resourceKind(),
                    identity,
                    expectedRuntime.image(),
                    entry.image()));
            }
        });
        actual.values().forEach(entry -> differences.add(new Difference(
            DifferenceKind.UNEXPECTED_RUNTIME,
            entry.resourceKind(),
            entry.runtimeId(),
            null,
            entry)));

        if (!resources.blocked().isEmpty() || !workerObservationComplete) {
            return new CheckResult(
                AssessmentCheck.RUNTIME_INVENTORY,
                AssessmentState.INCOMPLETE,
                "Runtime inventory contains resources or owner observations that cannot be assessed.",
                differences);
        }
        if (!differences.isEmpty()) {
            return new CheckResult(
                AssessmentCheck.RUNTIME_INVENTORY,
                AssessmentState.DRIFTED,
                "Manifested runtime resources differ from the exact labelled inventory.",
                differences);
        }
        return consistent(AssessmentCheck.RUNTIME_INVENTORY,
            "Expected manager and worker runtimes match the exact labelled inventory.");
    }

    private static boolean appendExpectedWorkers(
        Swarm swarm,
        Map<String, ExpectedRuntime> expected,
        List<Difference> differences) {
        Object rawWorkers = swarm.getObservation().get(OBSERVATION_WORKERS);
        if (!(rawWorkers instanceof List<?> workers)) {
            differences.add(sourceUnavailableWorkers());
            return false;
        }
        boolean complete = true;
        for (Object item : workers) {
            if (!(item instanceof Map<?, ?> worker)) {
                differences.add(sourceUnavailableWorkers());
                complete = false;
                continue;
            }
            String role = exactText(worker.get(OBSERVATION_ROLE));
            String instance = exactText(worker.get(OBSERVATION_INSTANCE));
            if (role == null || instance == null) {
                differences.add(sourceUnavailableWorkers());
                complete = false;
                continue;
            }
            String image = observedWorkerImage(worker.get(OBSERVATION_RUNTIME));
            ExpectedRuntime expectedWorker = ExpectedRuntime.observedWorker(role, instance, image);
            String identity = identity(expectedWorker.resourceKind(), role, instance);
            ExpectedRuntime previous = expected.putIfAbsent(identity, expectedWorker);
            if (previous != null) {
                differences.add(new Difference(
                    DifferenceKind.DUPLICATE_RUNTIME,
                    expectedWorker.resourceKind(),
                    identity,
                    previous.evidence(),
                    expectedWorker.evidence()));
            }
        }
        return complete;
    }

    private static Difference sourceUnavailableWorkers() {
        return new Difference(
            DifferenceKind.SOURCE_UNAVAILABLE,
            "control-plane",
            "workers",
            "canonical worker observations",
            null);
    }

    private static String observedWorkerImage(Object rawRuntime) {
        if (!(rawRuntime instanceof Map<?, ?> runtime)) {
            return null;
        }
        return exactText(runtime.get(OBSERVATION_IMAGE));
    }

    private static String exactText(Object value) {
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.trim())) {
            return null;
        }
        return text;
    }

    private static CheckResult rabbitCheck(RabbitTopologySnapshot rabbit) {
        if (!rabbit.manifest().available() || !rabbit.rabbit().available()) {
            return incomplete(AssessmentCheck.RABBIT_TOPOLOGY,
                "RabbitMQ topology cannot be assessed because an owner source is unavailable.",
                DifferenceKind.SOURCE_UNAVAILABLE, "rabbit-topology", "owner-sources", "available", null);
        }
        List<Difference> differences = new ArrayList<>();
        for (RabbitQueueSnapshot queue : rabbit.queues()) {
            if (!queue.present()) {
                differences.add(new Difference(
                    DifferenceKind.MISSING_RABBIT_RESOURCE,
                    "queue",
                    queue.name(),
                    "present",
                    "absent"));
            }
        }
        for (RabbitExchangeSnapshot exchange : rabbit.exchanges()) {
            if (!exchange.present()) {
                differences.add(new Difference(
                    DifferenceKind.MISSING_RABBIT_RESOURCE,
                    "exchange",
                    exchange.name(),
                    "present",
                    "absent"));
            }
        }
        if (!differences.isEmpty()) {
            return new CheckResult(
                AssessmentCheck.RABBIT_TOPOLOGY,
                AssessmentState.DRIFTED,
                "Manifested RabbitMQ resources are missing.",
                differences);
        }
        return consistent(AssessmentCheck.RABBIT_TOPOLOGY,
            "Manifested RabbitMQ resources are present.");
    }

    private static SwarmSnapshot snapshot(Swarm swarm) {
        return new SwarmSnapshot(
            swarm.getId(),
            swarm.getRunId(),
            swarm.getRuntimeIntent(),
            swarm.getWorkloadIntent(),
            swarm.getControllerState(),
            swarm.getWorkloadState(),
            swarm.getHealth(),
            swarm.getRuntimeResourceState(),
            swarm.getControllerStatusReceivedAt(),
            swarm.templateId(),
            swarm.controllerImage());
    }

    private static Map<String, Object> controlPlaneState(Swarm swarm) {
        return Map.of(
            "controllerState", swarm.getControllerState(),
            "health", swarm.getHealth(),
            "runtimeResourceState", swarm.getRuntimeResourceState(),
            "workloadState", swarm.getWorkloadState());
    }

    private static RuntimeResourceState expectedRuntimeState(RuntimeIntent intent) {
        return intent == RuntimeIntent.PRESENT ? RuntimeResourceState.PRESENT : RuntimeResourceState.ABSENT;
    }

    private static WorkloadState expectedWorkloadState(WorkloadIntent intent) {
        return intent == WorkloadIntent.RUNNING ? WorkloadState.RUNNING : WorkloadState.STOPPED;
    }

    private static AssessmentState overall(List<CheckResult> checks) {
        if (checks.stream().anyMatch(check -> check.state() == AssessmentState.DRIFTED)) {
            return AssessmentState.DRIFTED;
        }
        if (checks.stream().anyMatch(check -> check.state() == AssessmentState.INCOMPLETE)) {
            return AssessmentState.INCOMPLETE;
        }
        return AssessmentState.CONSISTENT;
    }

    private static CheckResult consistent(AssessmentCheck check, String summary) {
        return new CheckResult(check, AssessmentState.CONSISTENT, summary, List.of());
    }

    private static CheckResult incomplete(
        AssessmentCheck check,
        String summary,
        DifferenceKind kind,
        String resourceType,
        String resourceId,
        Object expected,
        Object actual) {
        return new CheckResult(check, AssessmentState.INCOMPLETE, summary,
            List.of(new Difference(kind, resourceType, resourceId, expected, actual)));
    }

    private static CheckResult drifted(
        AssessmentCheck check,
        String summary,
        DifferenceKind kind,
        String resourceType,
        String resourceId,
        Object expected,
        Object actual) {
        return new CheckResult(check, AssessmentState.DRIFTED, summary,
            List.of(new Difference(kind, resourceType, resourceId, expected, actual)));
    }

    private static String identity(String resourceKind, String role, String instance) {
        return String.join("/", resourceKind, role, instance);
    }

    private static List<RuntimeEntry> concat(List<RuntimeEntry> first, List<RuntimeEntry> second) {
        List<RuntimeEntry> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.trim())) {
            throw error(HttpStatus.BAD_REQUEST, field + " must be non-blank and normalized");
        }
        return value;
    }

    private static String optionalText(String value, String field) {
        if (value == null) {
            return null;
        }
        return requireText(value, field);
    }

    private static RuntimeDebugException error(HttpStatus status, String message) {
        return new RuntimeDebugException(status, message);
    }

    private record ExpectedRuntime(
        String runtimeId,
        String resourceKind,
        String role,
        String instance,
        String image,
        Object evidence) {
        private static ExpectedRuntime fromManifest(RuntimeOwnershipManifest.RuntimeObject object) {
            return new ExpectedRuntime(
                object.runtimeId(),
                object.resourceKind(),
                object.role(),
                object.instance(),
                object.image(),
                object);
        }

        private static ExpectedRuntime observedWorker(String role, String instance, String image) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("resourceKind", PocketHiveDockerLabels.RESOURCE_KIND_WORKER);
            evidence.put("role", role);
            evidence.put("instance", instance);
            if (image != null) {
                evidence.put("image", image);
            }
            return new ExpectedRuntime(
                null,
                PocketHiveDockerLabels.RESOURCE_KIND_WORKER,
                role,
                instance,
                image,
                Map.copyOf(evidence));
        }
    }
}
