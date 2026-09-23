package io.pockethive.orchestrator.app;

import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Responsibility: choose the requested, active or observed run for the existing file journal query.
 * Must not: perform file IO, decode entries, construct paths or change registry state.
 * Contract: RESP-SWARM-FILE-JOURNAL — docs/architecture/runtime-responsibilities.md#resp-swarm-file-journal.
 */
@Service
public class SwarmFileJournalQuery {
    private final SwarmStore store;
    private final SwarmJournalFiles files;

    public SwarmFileJournalQuery(SwarmStore store, SwarmJournalFiles files) {
        this.store = store;
        this.files = files;
    }

    public List<Map<String, Object>> read(String swarmId, String requestedRunId, String severityFilter) {
        String cleanedId;
        try {
            cleanedId = RuntimeFilesystemLayout.requireSegment(swarmId, "swarmId");
        } catch (IllegalArgumentException ex) {
            return null;
        }
        String runId = resolveRunId(cleanedId, requestedRunId);
        return runId == null ? null : files.read(cleanedId, runId, severityFilter);
    }

    private String resolveRunId(String swarmId, String requestedRunId) {
        String candidate = requestedRunId == null ? null : requestedRunId.trim();
        if (candidate != null && !candidate.isBlank()) {
            return candidate;
        }
        String active = store.find(swarmId).map(Swarm::getRunId).orElse(null);
        if (active != null && !active.isBlank()) {
            return active;
        }
        return files.latestRunDirectory(swarmId);
    }
}
