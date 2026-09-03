package io.pockethive.mcp.adapter.persistence;

import io.pockethive.mcp.application.UploadCoordinationSnapshot;
import io.pockethive.mcp.domain.AgentSessionSnapshot;
import io.pockethive.mcp.domain.ScenarioWorkflowSnapshot;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Responsibility: Represent the single persisted MCP coordination-state document.
 * Must not: Mutate repositories, migrate schemas, or decide domain transitions.
 * Contract: {@link CoordinationStateSchema}.
 */
record CoordinationStateDocument(
    int schemaVersion,
    Map<String, AgentSessionSnapshot> sessions,
    Map<String, ScenarioWorkflowSnapshot> workflows,
    Map<String, List<Map<String, Object>>> generatedFiles,
    UploadCoordinationSnapshot uploadCoordination
) {
    CoordinationStateDocument {
        sessions = sessions == null ? null : Map.copyOf(new TreeMap<>(sessions));
        workflows = workflows == null ? null : Map.copyOf(new TreeMap<>(workflows));
        generatedFiles = generatedFiles == null ? null : Map.copyOf(new TreeMap<>(generatedFiles));
    }

    static CoordinationStateDocument empty() {
        return new CoordinationStateDocument(CoordinationStateSchema.CURRENT_VERSION, Map.of(), Map.of(), Map.of(),
            UploadCoordinationSnapshot.empty());
    }
}
