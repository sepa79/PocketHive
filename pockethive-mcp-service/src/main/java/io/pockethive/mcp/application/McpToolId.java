package io.pockethive.mcp.application;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Responsibility: Define every canonical external MCP tool identifier and its owning service.
 * Must not: Describe schemas, execute tools, or select runtime configuration.
 * Contract: docs/mcp/README.md.
 */
public enum McpToolId {
    SCENARIO_LIST(ToolOwner.SCENARIO_MANAGER),
    SCENARIO_GET(ToolOwner.SCENARIO_MANAGER),
    SCENARIO_RAW_READ(ToolOwner.SCENARIO_MANAGER),
    SCENARIO_SCHEMA_READ(ToolOwner.SCENARIO_MANAGER),
    SCENARIO_TEMPLATE_READ(ToolOwner.SCENARIO_MANAGER),
    SCENARIO_BUNDLE_TREE_READ(ToolOwner.SCENARIO_MANAGER),
    SCENARIO_BUNDLE_FILE_READ(ToolOwner.SCENARIO_MANAGER),
    SCENARIO_SUTS_LIST(ToolOwner.SCENARIO_MANAGER),
    SCENARIO_SUT_GET(ToolOwner.SCENARIO_MANAGER),
    SCENARIO_CONTRACTS_GET(ToolOwner.SCENARIO_MANAGER),
    SCENARIO_CAPABILITIES_GET(ToolOwner.SCENARIO_MANAGER),
    SCENARIO_TEMPLATES_CATALOG(ToolOwner.SCENARIO_MANAGER),
    SWARM_LIST(ToolOwner.ORCHESTRATOR),
    SWARM_GET(ToolOwner.ORCHESTRATOR),
    SWARM_CREATE(ToolOwner.ORCHESTRATOR),
    SWARM_START(ToolOwner.ORCHESTRATOR),
    SWARM_WAIT_READY(ToolOwner.ORCHESTRATOR),
    SWARM_STOP(ToolOwner.ORCHESTRATOR),
    SWARM_REMOVE(ToolOwner.ORCHESTRATOR),
    DEBUG_JOURNAL(ToolOwner.ORCHESTRATOR),
    DEBUG_JOURNAL_RUNS(ToolOwner.ORCHESTRATOR),
    DEBUG_HIVE_JOURNAL(ToolOwner.ORCHESTRATOR),
    DEBUG_TAP(ToolOwner.ORCHESTRATOR),
    DEBUG_TAP_READ(ToolOwner.ORCHESTRATOR),
    DEBUG_TAP_CLOSE(ToolOwner.ORCHESTRATOR),
    COMPONENT_CONFIG_PREVIEW(ToolOwner.ORCHESTRATOR),
    COMPONENT_CONFIG_UPDATE(ToolOwner.ORCHESTRATOR),
    RUNTIME_CLEANUP_PLAN(ToolOwner.ORCHESTRATOR),
    RUNTIME_TAIL_WORKER_LOGS(ToolOwner.ORCHESTRATOR),
    RUNTIME_GET_WORKER_VERSION(ToolOwner.ORCHESTRATOR),
    RUNTIME_LIST_WORKERS(ToolOwner.ORCHESTRATOR),
    RUNTIME_INSPECT_WORKER(ToolOwner.ORCHESTRATOR),
    RUNTIME_ASSESS_SWARM(ToolOwner.ORCHESTRATOR),
    RUNTIME_DIFF_SWARM_RUNTIME(ToolOwner.ORCHESTRATOR),
    RUNTIME_CONTROL_PLANE_STATUS(ToolOwner.ORCHESTRATOR),
    RUNTIME_RABBIT_TOPOLOGY_SNAPSHOT(ToolOwner.ORCHESTRATOR),
    RUNTIME_SWARM_TIMELINE(ToolOwner.ORCHESTRATOR),
    RUNTIME_MANIFEST_VALIDATE(ToolOwner.ORCHESTRATOR),
    RUNTIME_CLEANUP_EXECUTE(ToolOwner.ORCHESTRATOR),
    AGENT_SESSION_CREATE(ToolOwner.MCP),
    AGENT_SESSION_GET(ToolOwner.MCP),
    AGENT_SESSION_LIST_WORKFLOWS(ToolOwner.MCP),
    AGENT_SESSION_CLOSE(ToolOwner.MCP),
    SCENARIO_WORKFLOW_CREATE(ToolOwner.MCP),
    SCENARIO_WORKFLOW_LIST(ToolOwner.MCP),
    SCENARIO_WORKFLOW_GET(ToolOwner.MCP),
    SCENARIO_WORKFLOW_ANSWER(ToolOwner.MCP),
    SCENARIO_WORKFLOW_QUESTION(ToolOwner.MCP),
    SCENARIO_WORKFLOW_ANSWER_SUBMIT(ToolOwner.MCP),
    SCENARIO_WORKFLOW_REVIEW_PREPARE(ToolOwner.MCP),
    SCENARIO_WORKFLOW_REVIEW_SUBMIT(ToolOwner.MCP),
    SCENARIO_WORKFLOW_GENERATE(ToolOwner.MCP),
    SCENARIO_WORKFLOW_CANCEL(ToolOwner.MCP),
    SCENARIO_BUNDLE_VALIDATION_PREPARE(ToolOwner.MCP),
    SCENARIO_BUNDLE_DIRECT_VALIDATION_PREPARE(ToolOwner.MCP),
    SCENARIO_BUNDLE_VALIDATION_RECEIPT_GET(ToolOwner.MCP),
    SCENARIO_BUNDLE_PUBLICATION_PREPARE(ToolOwner.MCP),
    SCENARIO_BUNDLE_PUBLICATION_ATTEMPT_GET(ToolOwner.MCP),
    SCENARIO_BUNDLE_PUBLICATION_RECONCILE(ToolOwner.MCP);

    private static final Map<String, McpToolId> BY_EXTERNAL_NAME = Arrays.stream(values())
        .collect(Collectors.toUnmodifiableMap(McpToolId::externalName, Function.identity()));

    private final ToolOwner owner;

    McpToolId(ToolOwner owner) {
        this.owner = owner;
    }

    @JsonValue
    public String externalName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public ToolOwner owner() {
        return owner;
    }

    public static McpToolId require(String externalName) {
        McpToolId result = BY_EXTERNAL_NAME.get(externalName);
        if (result == null) {
            throw new IllegalArgumentException("Unknown tool: " + externalName);
        }
        return result;
    }
}
