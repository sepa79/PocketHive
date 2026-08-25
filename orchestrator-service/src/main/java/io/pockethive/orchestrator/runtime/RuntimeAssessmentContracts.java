package io.pockethive.orchestrator.runtime;

import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.RabbitTopologySnapshot;
import io.pockethive.orchestrator.runtime.RuntimeDebugContracts.ResourceListResponse;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.Health;
import io.pockethive.swarm.model.lifecycle.RuntimeIntent;
import io.pockethive.swarm.model.lifecycle.RuntimeResourceState;
import io.pockethive.swarm.model.lifecycle.WorkloadIntent;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.time.Instant;
import java.util.List;

public final class RuntimeAssessmentContracts {
    private RuntimeAssessmentContracts() {
    }

    public enum AssessmentState {
        CONSISTENT,
        DRIFTED,
        INCOMPLETE
    }

    public enum AssessmentCheck {
        REGISTRY,
        CONTROL_PLANE,
        OWNERSHIP_MANIFEST,
        RUNTIME_INVENTORY,
        RABBIT_TOPOLOGY
    }

    public enum DifferenceKind {
        SOURCE_UNAVAILABLE,
        RUN_ID_MISMATCH,
        MISSING_RUNTIME,
        UNEXPECTED_RUNTIME,
        RUNTIME_ID_MISMATCH,
        IMAGE_MISMATCH,
        DUPLICATE_RUNTIME,
        BLOCKED_RUNTIME,
        MISSING_RABBIT_RESOURCE,
        CONTROL_PLANE_STATE_MISMATCH
    }

    public record AssessmentRequest(String swarmId, String runId) {
    }

    public record Difference(
        DifferenceKind kind,
        String resourceType,
        String resourceId,
        Object expected,
        Object actual) {
    }

    public record CheckResult(
        AssessmentCheck check,
        AssessmentState state,
        String summary,
        List<Difference> differences) {
        public CheckResult {
            differences = differences == null ? List.of() : List.copyOf(differences);
        }
    }

    public record SwarmSnapshot(
        String id,
        String runId,
        RuntimeIntent runtimeIntent,
        WorkloadIntent workloadIntent,
        ControllerState controllerState,
        WorkloadState workloadState,
        Health health,
        RuntimeResourceState runtimeResourceState,
        Instant observedAt,
        String templateId,
        String controllerImage) {
    }

    public record AssessmentResponse(
        String assessmentContractVersion,
        AssessmentState overall,
        String swarmId,
        String runId,
        Instant assessedAt,
        List<CheckResult> checks,
        SwarmSnapshot swarm,
        ResourceListResponse resources,
        RuntimeOwnershipManifest manifest,
        RabbitTopologySnapshot rabbitTopology) {
        public AssessmentResponse {
            checks = List.copyOf(checks);
        }
    }
}
