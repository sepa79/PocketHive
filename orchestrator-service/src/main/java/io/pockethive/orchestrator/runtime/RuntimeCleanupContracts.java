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
        Boolean includeRabbit) {
    }

    public record ExecuteRequest(
        String swarmId,
        String runId,
        Boolean includeRunning,
        Boolean includeRabbit,
        String candidateSetHash,
        List<String> candidateIds,
        String idempotencyKey,
        String reason,
        String actor) {
    }

    public record Plan(
        String computeAdapter,
        String swarmId,
        String runId,
        boolean includeRunning,
        boolean includeRabbit,
        String candidateSetHash,
        String executionRisk,
        List<Candidate> candidates,
        List<Blocked> blocked) {
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


}
