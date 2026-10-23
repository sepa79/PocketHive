package io.pockethive.orchestrator.planner;

import io.pockethive.journal.api.JournalRunMetadata;
import io.pockethive.journal.api.JournalRunQueries;
import io.pockethive.journal.api.SwarmRunSummary;
import io.pockethive.orchestrator.domain.SwarmStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Responsibility: compose a validated Planner selection with an exact PH journal run.
 * Must not: infer Activity execution, own metadata SQL or change Planner state.
 * Contract: RESP-POCKETPLANNER-INTEGRATION — docs/architecture/runtime-responsibilities.md#resp-pocketplanner-integration.
 */
@Service
public final class PlannerRunService {
    private final PocketPlannerClient planner;
    private final JournalRunMetadata metadata;
    private final JournalRunQueries runs;
    private final String sink;
    private final SwarmStore swarms;

    public PlannerRunService(PocketPlannerClient planner, JournalRunMetadata metadata, JournalRunQueries runs, SwarmStore swarms,
                             @Value("${pockethive.journal.sink:postgres}") String sink) {
        this.planner = planner;
        this.metadata = metadata;
        this.runs = runs;
        this.sink = sink;
        this.swarms = swarms;
    }

    public SwarmRunSummary current(String swarmId) {
        var swarm = swarms.find(swarmId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Registered swarm not found"));
        return get(swarmId, swarm.getRunId());
    }

    public SwarmRunSummary get(String swarmId, String runId) {
        requirePostgres();
        SwarmRunSummary result = runs.metadataSummary(swarmId, runId);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exact journal run not found");
        return result;
    }

    public SwarmRunSummary assign(String swarmId, String runId, PlannerSelection selection, String authorization) {
        var link = validate(selection, authorization);
        try {
            SwarmRunSummary result = metadata.linkPlanner(swarmId, runId, link);
            if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Exact journal run not found");
            return result;
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    public io.pockethive.journal.api.PlannerRunLink validate(PlannerSelection selection, String authorization) {
        requirePostgres();
        return planner.resolve(selection, authorization);
    }

    private void requirePostgres() {
        if (!"postgres".equals(sink)) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Planner links require the PostgreSQL journal");
        }
    }
}
