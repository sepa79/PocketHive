package io.pockethive.orchestrator.app;

import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import io.pockethive.journal.api.JournalEventQueries;
import io.pockethive.journal.api.JournalRunQueries;
import io.pockethive.journal.api.JournalRunSummary;
import io.pockethive.journal.api.JournalPageResponse;
import io.pockethive.orchestrator.domain.SwarmStore;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Responsibility: resolve stored-journal runs and preserve registry-aware absence and archive precedence.
 * Must not: execute SQL, decode rows, authorize HTTP or mutate registry/journal state.
 * Contract: RESP-JOURNAL-RUN-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-run-queries;
 * RESP-JOURNAL-EVENT-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-event-queries.
 */
@Service
public class SwarmStoredJournalQuery {
    private final SwarmStore store;
    private final SwarmJournalRunSelector runs;
    private final JournalEventQueries events;
    private final JournalRunQueries runQueries;

    public SwarmStoredJournalQuery(SwarmStore store, SwarmJournalRunSelector runs, JournalEventQueries events, JournalRunQueries runQueries) {
        this.runQueries = runQueries;
        this.store = store;
        this.runs = runs;
        this.events = events;
    }

    public List<JournalRunSummary> runs(String swarmId) {
        String cleanedId = sanitizeSwarmId(swarmId);
        if (cleanedId == null) {
            return null;
        }
        List<JournalRunSummary> result = runQueries.swarmRuns(cleanedId);
        return result.isEmpty() && store.find(cleanedId).isEmpty() ? null : result;
    }

    public JournalPageResponse page(String swarmId, String requestedRunId, String correlationId,
        String severity, Instant beforeTs, Long beforeId, int limit) {
        String cleanedId = sanitizeSwarmId(swarmId);
        if (cleanedId == null) {
            return null;
        }
        String runId = resolveRunId(cleanedId, requestedRunId);
        if (runId == null) {
            return null;
        }
        UUID capture = events.pinnedSwarmCapture(cleanedId, runId);
        if (capture != null) {
            return events.archivedSwarmPage(capture, cleanedId, runId, correlationId, severity, beforeTs, beforeId, limit);
        }
        JournalPageResponse result = events.swarmPage(cleanedId, runId, correlationId, severity, beforeTs, beforeId, limit);
        return result.items().isEmpty() && store.find(cleanedId).isEmpty() ? null : result;
    }

    public List<Map<String, Object>> timeline(String swarmId, String requestedRunId, String severity) {
        String cleanedId = sanitizeSwarmId(swarmId);
        if (cleanedId == null) {
            return null;
        }
        String runId = resolveRunId(cleanedId, requestedRunId);
        if (runId == null) {
            return null;
        }
        UUID capture = events.pinnedSwarmCapture(cleanedId, runId);
        if (capture != null) {
            return events.archivedSwarmTimeline(capture, cleanedId, runId, severity);
        }
        List<Map<String, Object>> result = events.swarmTimeline(cleanedId, runId, severity);
        return result.isEmpty() && store.find(cleanedId).isEmpty() ? null : result;
    }

    /** Shared by stored reads and pinning, after swarm-id boundary validation. */
    public String resolveRunId(String swarmId, String requestedRunId) {
        return runs.resolve(swarmId, requestedRunId, () -> events.latestSwarmRun(swarmId));
    }

    private static String sanitizeSwarmId(String id) {
        try {
            return RuntimeFilesystemLayout.requireSegment(id, "swarmId");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
