package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.AgentSession;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.ScenarioWorkflow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Duration;
import java.time.Instant;

/**
 * Responsibility: Define the closed coordination state repository application contract.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public interface CoordinationStateRepository {
    Optional<AgentSession> findSession(String sessionId);

    Optional<ScenarioWorkflow> findWorkflow(String workflowId);

    List<ScenarioWorkflow> findWorkflows(List<String> workflowIds);

    List<Map<String, Object>> findGeneratedFiles(String workflowId);

    void createSession(AgentSession session);

    void saveSession(AgentSession session);

    void createWorkflow(AgentSession session, ScenarioWorkflow workflow);

    void saveWorkflow(ScenarioWorkflow workflow, List<Map<String, Object>> generatedFiles);

    void saveWorkflow(ScenarioWorkflow workflow);

    void saveWorkflowAndRemoveGeneratedFiles(ScenarioWorkflow workflow);

    long countOpenSessions(PrincipalKey principal);

    UploadCoordinationSnapshot loadUploadCoordination();

    void saveUploadCoordination(UploadCoordinationSnapshot uploadCoordination);

    void maintainSessions(Instant now, Duration terminalRetention);
}
