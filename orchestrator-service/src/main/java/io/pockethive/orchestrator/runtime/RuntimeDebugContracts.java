package io.pockethive.orchestrator.runtime;

import java.util.List;
import java.util.Map;

public final class RuntimeDebugContracts {
    public static final String RUNTIME_DEBUG_CONTRACT_VERSION = "2";
    public static final String CLEANUP_CONTRACT_VERSION = "2";

    private RuntimeDebugContracts() {
    }

    public record Capabilities(
        String runtimeDebugContractVersion,
        String cleanupContractVersion,
        boolean runtimeDebugReadsBackedByOrchestrator,
        boolean cleanupPlanHasExecutionRisk,
        boolean cleanupPlanUsesApprovalFields,
        boolean cleanupExecuteRequiresCandidateSetHash,
        boolean rabbitTopologyExactByDefault,
        boolean cleanupSupportsRegisteredStateOverride) {

        public static Capabilities current() {
            return new Capabilities(
                RUNTIME_DEBUG_CONTRACT_VERSION,
                CLEANUP_CONTRACT_VERSION,
                true,
                true,
                false,
                true,
                true,
                true);
        }
    }

    public record ResourceListRequest(
        String computeAdapter,
        String swarmId,
        String runId,
        Boolean includeManagers) {
    }

    public record RuntimeTargetRequest(
        String computeAdapter,
        String swarmId,
        String runId,
        String runtimeId,
        String instance,
        String role,
        String resourceKind) {
    }

    public record RuntimeLogsRequest(
        String computeAdapter,
        String swarmId,
        String runId,
        String runtimeId,
        String instance,
        String role,
        String resourceKind,
        Integer tailLines,
        String since) {
    }

    public record ResourceListResponse(
        String computeAdapter,
        String swarmId,
        String runId,
        Counts counts,
        List<RuntimeEntry> workers,
        List<RuntimeEntry> managers,
        List<BlockedResource> blocked) {
    }

    public record Counts(int workers, int managers, int blocked) {
    }

    public record RuntimeEntry(
        String runtimeId,
        String runtimeType,
        String name,
        String resourceKind,
        String swarmId,
        String runId,
        String role,
        String instance,
        String logicalName,
        String state,
        boolean running,
        String image,
        String imageTag,
        String imageDigest,
        String declaredVersion,
        String reportedVersion,
        String createdAt,
        String startedAt,
        String finishedAt,
        String registryStatus,
        Map<String, String> labels) {
    }

    public record RuntimeTarget(
        String runtimeId,
        String runtimeType,
        String name,
        String resourceKind,
        String swarmId,
        String runId,
        String role,
        String instance,
        String logicalName,
        String state,
        String image,
        Map<String, String> labels) {
    }

    public record BlockedResource(
        String runtimeId,
        String runtimeType,
        String name,
        String state,
        String reason,
        Map<String, String> labels) {
    }

    public record RuntimeLogsResponse(
        RuntimeTarget target,
        int tailLines,
        String since,
        boolean redacted,
        int lineCount,
        String logs) {
    }

    public record RuntimeVersionResponse(
        RuntimeTarget target,
        String declaredVersion,
        String image,
        String imageTag,
        String imageDigest,
        String reportedVersion,
        String reportedVersionSource) {
    }

    public record RuntimeInspectResponse(
        RuntimeTarget target,
        Map<String, Object> source,
        Map<String, Object> state,
        String createdAt,
        Integer restartCount,
        String restartPolicy,
        List<Map<String, Object>> mounts,
        List<String> networks) {
    }
}
