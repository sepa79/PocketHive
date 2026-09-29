package io.pockethive.orchestrator.app;

import io.pockethive.controlplane.filesystem.RuntimeFilesystemLayout;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Responsibility: query the file journal using the shared run selector and the file-read port.
 * Must not: perform file IO, decode entries, construct paths or change registry state.
 * Contract: RESP-SWARM-FILE-JOURNAL — docs/architecture/runtime-responsibilities.md#resp-swarm-file-journal.
 */
@Service
public class SwarmFileJournalQuery {
    private final SwarmJournalRunSelector runs;
    private final SwarmJournalFiles files;

    public SwarmFileJournalQuery(SwarmJournalRunSelector runs, SwarmJournalFiles files) {
        this.runs = runs;
        this.files = files;
    }

    public List<Map<String, Object>> read(String swarmId, String requestedRunId, String severityFilter) {
        String cleanedId;
        try {
            cleanedId = RuntimeFilesystemLayout.requireSegment(swarmId, "swarmId");
        } catch (IllegalArgumentException ex) {
            return null;
        }
        String runId = runs.resolve(cleanedId, requestedRunId, () -> files.latestRunDirectory(cleanedId));
        return runId == null ? null : files.read(cleanedId, runId, severityFilter);
    }

}
