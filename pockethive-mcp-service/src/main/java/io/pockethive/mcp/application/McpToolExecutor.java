package io.pockethive.mcp.application;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.pockethive.mcp.adapter.mcp.McpCaller;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Responsibility: Enforce MCP tool scope and dispatch each descriptor to its declared owner handler.
 * Must not: Implement owner API mapping, QA workflow behavior, persistence, or response projection.
 * Contract: docs/mcp/README.md.
 */
@Service
public final class McpToolExecutor {
    private final ScenarioManagerToolExecutor scenarioTools;
    private final OrchestratorToolExecutor orchestratorTools;
    private final QaWorkflowToolExecutor workflowTools;

    public McpToolExecutor(ScenarioManagerToolExecutor scenarioTools,
                           OrchestratorToolExecutor orchestratorTools,
                           QaWorkflowToolExecutor workflowTools) {
        this.scenarioTools = scenarioTools;
        this.orchestratorTools = orchestratorTools;
        this.workflowTools = workflowTools;
    }

    public Object execute(ToolDescriptor descriptor, McpSyncServerExchange exchange,
                          Map<String, Object> arguments) {
        McpCaller caller = McpCaller.from(exchange.transportContext());
        requireScope(caller, descriptor.requiredScope());
        return switch (descriptor.owner()) {
            case MCP -> workflowTools.execute(descriptor.toolId(), exchange, caller, arguments);
            case SCENARIO_MANAGER -> scenarioTools.execute(descriptor.toolId(), arguments);
            case ORCHESTRATOR -> orchestratorTools.execute(descriptor.toolId(), arguments);
        };
    }

    private static void requireScope(McpCaller caller, String scope) {
        if (!caller.scopes().contains(scope)) {
            throw new ToolExecutionException("MCP_SCOPE_REQUIRED", scope);
        }
    }
}
