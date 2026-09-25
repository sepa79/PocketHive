package io.pockethive.mcp.application;

import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Responsibility: Enforce MCP tool scope and dispatch each descriptor to its declared owner
 * handler. Must not: Implement owner API mapping, QA workflow behavior, persistence, or response
 * projection. Contract: docs/mcp/README.md.
 */
@Service
public final class McpToolExecutor {
  private final ScenarioManagerToolExecutor scenarioTools;
  private final OrchestratorToolExecutor orchestratorTools;
  private final QaWorkflowToolExecutor workflowTools;

  public McpToolExecutor(
      ScenarioManagerToolExecutor scenarioTools,
      OrchestratorToolExecutor orchestratorTools,
      QaWorkflowToolExecutor workflowTools) {
    this.scenarioTools = scenarioTools;
    this.orchestratorTools = orchestratorTools;
    this.workflowTools = workflowTools;
  }

  public Object execute(
      ToolDescriptor descriptor,
      McpCaller caller,
      ClientInteraction exchange,
      Map<String, Object> arguments) {
    caller.requireScope(descriptor.requiredScope());
    return switch (descriptor.owner()) {
      case MCP -> workflowTools.execute(descriptor.toolId(), exchange, caller, arguments);
      case SCENARIO_MANAGER -> scenarioTools.execute(descriptor.toolId(), arguments);
      case ORCHESTRATOR -> orchestratorTools.execute(descriptor.toolId(), arguments);
    };
  }
}
