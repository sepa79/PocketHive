package io.pockethive.orchestrator.runtime;

import java.util.List;
import java.util.Map;

public final class RuntimeDebugContracts {
    public static final String RUNTIME_DEBUG_CONTRACT_VERSION = "3";
    public static final String CLEANUP_CONTRACT_VERSION = "3";

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
        String swarmId,
        String runId,
        Boolean includeManagers) {
    }

    public record RuntimeTargetRequest(
        String swarmId,
        String runId,
        String runtimeId,
        String instance,
        String role,
        String resourceKind) {
    }

    public record RuntimeLogsRequest(
        String swarmId,
        String runId,
        String runtimeId,
        String instance,
        String role,
        String resourceKind,
        Integer tailLines,
        String since) {
    }

    public record RabbitTopologyRequest(
        String swarmId,
        String runId) {
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

    public record RabbitTopologySnapshot(
        String computeAdapter,
        String swarmId,
        String runId,
        SourceSummary manifest,
        SourceSummary rabbit,
        boolean exactOnly,
        List<RabbitQueueSnapshot> queues,
        List<RabbitExchangeSnapshot> exchanges,
        List<RabbitQueueSnapshot> unmanagedDiagnostics) {
    }

    public record SourceSummary(
        boolean available,
        String reason,
        String error) {
        public static SourceSummary present() {
            return new SourceSummary(true, null, null);
        }

        public static SourceSummary missing(String reason) {
            return new SourceSummary(false, reason, null);
        }
    }

    public record RabbitQueueSnapshot(
        String name,
        boolean present,
        Long messages,
        Integer consumers,
        String state,
        Boolean durable,
        Boolean autoDelete,
        Boolean diagnosticOnly,
        String reason) {
    }

    public record RabbitExchangeSnapshot(
        String name,
        boolean present,
        String type,
        Boolean durable,
        Boolean autoDelete,
        String reason) {
    }
}
