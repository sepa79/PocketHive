package io.pockethive.mcp.application;

import static io.pockethive.mcp.application.ToolArguments.number;
import static io.pockethive.mcp.application.ToolArguments.text;

import io.pockethive.mcp.adapter.mcp.McpCaller;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import io.pockethive.mcp.domain.AgentSession;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Execute MCP agent-session lifecycle tools and their workflow-list projection.
 * Must not: Capture QA requirements, generate bundles, publish bundles, or call owner APIs.
 * Contract: docs/mcp/README.md.
 */
@Component
final class AgentSessionToolExecutor {
    private final WorkflowAccess workflows;
    private final PocketHiveMcpProperties properties;
    private final CoordinationStateRepository state;
    private final WorkflowProjection projection;
    private final Clock clock;
    private final Map<McpToolId, ToolAction> actions;

    AgentSessionToolExecutor(WorkflowAccess workflows, PocketHiveMcpProperties properties,
                             CoordinationStateRepository state, WorkflowProjection projection, Clock clock) {
        this.workflows = workflows;
        this.properties = properties;
        this.state = state;
        this.projection = projection;
        this.clock = clock;
        this.actions = Map.of(
            McpToolId.AGENT_SESSION_CREATE, (caller, input) -> create(caller),
            McpToolId.AGENT_SESSION_GET, (caller, input) -> projection.session(
                workflows.requireSession(text(input, "agentSessionId"), caller)),
            McpToolId.AGENT_SESSION_LIST_WORKFLOWS, (caller, input) -> listWorkflows(
                workflows.requireSession(text(input, "agentSessionId"), caller)),
            McpToolId.SCENARIO_WORKFLOW_LIST, (caller, input) -> listWorkflows(
                workflows.requireSession(text(input, "agentSessionId"), caller)),
            McpToolId.AGENT_SESSION_CLOSE, this::close);
    }

    boolean supports(McpToolId toolId) {
        return actions.containsKey(toolId);
    }

    Object execute(McpToolId toolId, McpCaller caller, Map<String, Object> input) {
        ToolAction action = actions.get(toolId);
        if (action == null) {
            throw new ToolExecutionException("TOOL_HANDLER_MISSING", toolId.externalName());
        }
        return action.execute(caller, input);
    }

    private Object create(McpCaller caller) {
        Instant now = clock.instant();
        state.maintainSessions(now, properties.closedSessionRetention());
        String id = "as-" + UUID.randomUUID();
        AgentSession session = AgentSession.open(id, caller.principal(), now, properties.openSessionTtl());
        state.createSession(session);
        return projection.session(session);
    }

    private Object close(McpCaller caller, Map<String, Object> input) {
        AgentSession session = workflows.requireSession(text(input, "agentSessionId"), caller);
        session.close(number(input, "expectedRevision"), clock.instant());
        state.saveSession(session);
        return projection.session(session);
    }

    private Map<String, Object> listWorkflows(AgentSession session) {
        List<Map<String, Object>> items = state.findWorkflows(session.workflowIds()).stream()
            .map(projection::workflow)
            .toList();
        return Map.of("agentSessionId", session.id(), "workflows", items, "count", items.size());
    }

    @FunctionalInterface
    private interface ToolAction {
        Object execute(McpCaller caller, Map<String, Object> input);
    }
}
