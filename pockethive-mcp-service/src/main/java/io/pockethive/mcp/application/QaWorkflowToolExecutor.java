package io.pockethive.mcp.application;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.pockethive.mcp.adapter.mcp.McpCaller;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Dispatch MCP-owned QA tools to their single bounded application handler.
 * Must not: Implement session, workflow, bundle, persistence, or projection behavior.
 * Contract: docs/mcp/README.md.
 */
@Component
final class QaWorkflowToolExecutor {
    private final BundleToolExecutor bundleTools;
    private final AgentSessionToolExecutor sessionTools;
    private final ScenarioWorkflowToolExecutor workflowTools;

    QaWorkflowToolExecutor(BundleToolExecutor bundleTools, AgentSessionToolExecutor sessionTools,
                           ScenarioWorkflowToolExecutor workflowTools) {
        this.bundleTools = bundleTools;
        this.sessionTools = sessionTools;
        this.workflowTools = workflowTools;
    }

    Object execute(McpToolId toolId, McpSyncServerExchange exchange, McpCaller caller,
                   Map<String, Object> input) {
        if (bundleTools.supports(toolId)) {
            return bundleTools.execute(toolId, input, caller);
        }
        if (sessionTools.supports(toolId)) {
            return sessionTools.execute(toolId, caller, input);
        }
        if (workflowTools.supports(toolId)) {
            return workflowTools.execute(toolId, exchange, caller, input);
        }
        throw new ToolExecutionException("TOOL_HANDLER_MISSING", toolId.externalName());
    }
}
