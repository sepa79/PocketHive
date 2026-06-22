package io.pockethive.orchestrator.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class RuntimeCleanupContracts {
    private RuntimeCleanupContracts() {
    }

    public record PlanRequest(
        String swarmId,
        String runId,
        Boolean includeRunning,
        Boolean includeRabbit,
        Boolean overrideRegisteredSwarmState) {
        public PlanRequest(String swarmId,
                           String runId,
                           Boolean includeRunning,
                           Boolean includeRabbit) {
            this(swarmId, runId, includeRunning, includeRabbit, false);
        }
    }

    public record ExecuteRequest(
        String swarmId,
        String runId,
        Boolean includeRunning,
        Boolean includeRabbit,
        Boolean overrideRegisteredSwarmState,
        String candidateSetHash,
        List<String> candidateIds,
        String idempotencyKey,
        String reason,
        String actor) {
        public ExecuteRequest(String swarmId,
                              String runId,
                              Boolean includeRunning,
                              Boolean includeRabbit,
                              String candidateSetHash,
                              List<String> candidateIds,
                              String idempotencyKey,
                              String reason,
                              String actor) {
            this(
                swarmId,
                runId,
                includeRunning,
                includeRabbit,
                false,
                candidateSetHash,
                candidateIds,
                idempotencyKey,
                reason,
                actor);
        }
    }

    public record Plan(
        String computeAdapter,
        String swarmId,
        String runId,
        boolean includeRunning,
        boolean includeRabbit,
        boolean overrideRegisteredSwarmState,
        String candidateSetHash,
        String executionRisk,
        List<Candidate> candidates,
        List<Blocked> blocked) {
        public Plan(String computeAdapter,
                    String swarmId,
                    String runId,
                    boolean includeRunning,
                    boolean includeRabbit,
                    String candidateSetHash,
                    String executionRisk,
                    List<Candidate> candidates,
                    List<Blocked> blocked) {
            this(
                computeAdapter,
                swarmId,
                runId,
                includeRunning,
                includeRabbit,
                false,
                candidateSetHash,
                executionRisk,
                candidates,
                blocked);
        }
    }

    public record Candidate(
        String candidateId,
        RuntimeCleanupAction action,
        String resourceId,
        String resourceType,
        String resourceKind,
        String role,
        String instance,
        String state,
        String image,
        Long queueDepth,
        Integer consumers,
        boolean running,
        boolean highRisk,
        String reason,
        Map<String, String> labels) {
    }

    public record Blocked(
        String candidateId,
        RuntimeCleanupAction action,
        String resourceId,
        String resourceType,
        String reason,
        Map<String, String> labels) {
    }

    public record ExecuteResponse(boolean idempotent, Evidence evidence) {
    }

    public record Evidence(
        String actor,
        String idempotencyKey,
        String computeAdapter,
        String swarmId,
        String runId,
        String candidateSetHash,
        List<String> candidateIds,
        List<CandidateResult> resultByCandidate,
        Instant startedAt,
        Instant finishedAt,
        List<String> errors) {
    }

    public record CandidateResult(
        String candidateId,
        RuntimeCleanupAction action,
        String resourceId,
        RuntimeCleanupStatus status,
        String error) {
    }
}
