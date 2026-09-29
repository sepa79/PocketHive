package io.pockethive.orchestrator.app;

import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * Responsibility: resolve an explicit or active journal run before consulting the selected storage observation.
 * Must not: execute storage IO directly, reconstruct paths or change registry state.
 * Contract: RESP-JOURNAL-EVENT-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-event-queries;
 * RESP-SWARM-FILE-JOURNAL — docs/architecture/runtime-responsibilities.md#resp-swarm-file-journal.
 */
@Service
public class SwarmJournalRunSelector {
    private final SwarmStore store;

    public SwarmJournalRunSelector(SwarmStore store) {
        this.store = store;
    }

    public String resolve(String swarmId, String requestedRunId, Supplier<String> observedRun) {
        String candidate = requestedRunId == null ? null : requestedRunId.trim();
        if (candidate != null && !candidate.isBlank()) {
            return candidate;
        }
        String active = store.find(swarmId).map(Swarm::getRunId).orElse(null);
        if (active != null && !active.isBlank()) {
            return active;
        }
        return observedRun.get();
    }
}
