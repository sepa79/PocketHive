package io.pockethive.orchestrator.app;

import io.pockethive.journal.api.JournalCaptures;
import io.pockethive.journal.api.PinRunRequest;
import io.pockethive.journal.api.PinRunResponse;
import org.springframework.stereotype.Service;

/**
 * Responsibility: resolve the journal run before invoking the capture port.
 * Must not: create archives, infer capture outcomes or authorize HTTP.
 * Contract: RESP-JOURNAL-WRITES — docs/architecture/runtime-responsibilities.md#resp-journal-writes.
 */
@Service
public class SwarmJournalPinning {
    private final SwarmStoredJournalQuery runs;
    private final JournalCaptures captures;

    public SwarmJournalPinning(SwarmStoredJournalQuery runs, JournalCaptures captures) {
        this.runs = runs;
        this.captures = captures;
    }

    public PinRunResponse pin(String swarmId, PinRunRequest request) {
        String runId = runs.resolveRunId(swarmId, request == null ? null : request.runId());
        return runId == null ? null : captures.pin(swarmId, runId, request);
    }
}
