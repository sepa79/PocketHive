package io.pockethive.journal.postgres;

import io.pockethive.journal.api.SwarmRunSummary;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: merge live and pinned run observations into a single read-only summary per swarm/run.
 * Must not: query storage, change stored statistics or decide retention.
 * Contract: RESP-JOURNAL-RUN-QUERIES — docs/architecture/runtime-responsibilities.md#resp-journal-run-queries.
 */
final class JournalRunSummaries {
    private JournalRunSummaries() {}

    static List<SwarmRunSummary> merge(List<SwarmRunSummary> pinned, List<SwarmRunSummary> live) {
        Map<Key, SwarmRunSummary> merged = new LinkedHashMap<>();
        for (SwarmRunSummary run : pinned) {
            if (run.runId() != null && run.swarmId() != null) {
                merged.put(new Key(run.swarmId(), run.runId()), run);
            }
        }
        for (SwarmRunSummary run : live) {
            if (run.runId() == null || run.swarmId() == null) {
                continue;
            }
            Key key = new Key(run.swarmId(), run.runId());
            SwarmRunSummary existing = merged.get(key);
            if (existing == null) {
                merged.put(key, run);
            } else if (existing.lastTs() == null || (run.lastTs() != null && run.lastTs().isAfter(existing.lastTs()))) {
                merged.put(key, new SwarmRunSummary(existing.swarmId(), existing.runId(),
                    existing.firstTs() != null ? existing.firstTs() : run.firstTs(), run.lastTs(),
                    Math.max(existing.entries(), run.entries()), true,
                    existing.scenarioId() != null ? existing.scenarioId() : run.scenarioId(),
                    existing.testPlan() != null ? existing.testPlan() : run.testPlan(),
                    existing.tags() != null ? existing.tags() : run.tags(),
                    existing.description() != null ? existing.description() : run.description()));
            }
        }
        return merged.values().stream()
            .sorted(Comparator.comparing(SwarmRunSummary::lastTs, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
            .toList();
    }

    private record Key(String swarmId, String runId) {}
}
