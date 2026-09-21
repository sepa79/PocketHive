package io.pockethive.mcp.application;

import io.pockethive.mcp.adapter.mcp.McpCaller;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import io.pockethive.mcp.domain.AgentSession;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.ScenarioWorkflow;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Enforce principal and session-state access to MCP-owned authoring workflows.
 * Must not: Mutate workflow content, build projections, or execute authoring actions.
 * Contract: docs/mcp/README.md.
 */
@Component
final class WorkflowAccess {
    private final CoordinationStateRepository state;
    private final PocketHiveMcpProperties properties;
    private final Clock clock;

    WorkflowAccess(CoordinationStateRepository state, PocketHiveMcpProperties properties, Clock clock) {
        this.state = state;
        this.properties = properties;
        this.clock = clock;
    }

    AgentSession requireSession(String id, McpCaller caller) {
        Instant now = clock.instant();
        state.maintainSessions(now, properties.closedSessionRetention());
        AgentSession session = state.findSession(id).orElse(null);
        if (session == null || !session.principal().equals(caller.principal())) {
            throw new ToolExecutionException("AGENT_SESSION_NOT_FOUND", id);
        }
        long revision = session.revision();
        session.expireAt(now);
        if (session.revision() != revision) {
            state.saveSession(session);
        }
        return session;
    }

    ScenarioWorkflow requireWorkflow(String id, McpCaller caller) {
        return requireWorkflow(id, caller.principal());
    }

    ScenarioWorkflow requireWorkflow(String id, PrincipalKey principal) {
        ScenarioWorkflow workflow = state.findWorkflow(id).orElse(null);
        if (workflow == null || !workflow.principal().equals(principal)) {
            throw new ToolExecutionException("SCENARIO_WORKFLOW_NOT_FOUND", id);
        }
        return workflow;
    }

    ScenarioWorkflow requireMutableWorkflow(String id, McpCaller caller) {
        ScenarioWorkflow workflow = requireWorkflow(id, caller);
        AgentSession session = requireSession(workflow.agentSessionId(), caller);
        if (session.state() != io.pockethive.mcp.domain.AgentSessionState.OPEN) {
            throw new ToolExecutionException("AGENT_SESSION_NOT_OPEN", session.id());
        }
        return workflow;
    }
}
