package io.pockethive.mcp.application;

import static io.pockethive.mcp.application.ToolArguments.body;
import static io.pockethive.mcp.application.ToolArguments.booleanValue;
import static io.pockethive.mcp.application.ToolArguments.optionalQuery;
import static io.pockethive.mcp.application.ToolArguments.optionalSegmentQuery;
import static io.pockethive.mcp.application.ToolArguments.requiredNullableText;
import static io.pockethive.mcp.application.ToolArguments.query;
import static io.pockethive.mcp.application.ToolArguments.queryOr;
import static io.pockethive.mcp.application.ToolArguments.segment;
import static io.pockethive.mcp.application.ToolArguments.text;

import io.pockethive.swarm.model.NetworkMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Map Orchestrator-owned MCP tools to the Orchestrator public-ingress API.
 * Must not: Reimplement Orchestrator domain decisions, lifecycle state, or success postconditions.
 * Contract: docs/mcp/README.md and docs/ORCHESTRATOR-REST.md.
 */
@Component
final class OrchestratorToolExecutor {
    private static final String PREFIX = "/orchestrator";

    private final OwnerApiPort owners;
    private final SwarmReadinessObserver readiness;

    OrchestratorToolExecutor(OwnerApiPort owners, SwarmReadinessObserver readiness) {
        this.owners = owners;
        this.readiness = readiness;
    }

    boolean supports(McpToolId toolId) {
        return toolId.owner() == ToolOwner.ORCHESTRATOR;
    }

    Object execute(McpToolId toolId, Map<String, Object> input) {
        return switch (toolId) {
            case SWARM_LIST -> owners.get(PREFIX + "/api/swarms");
            case SWARM_GET -> owners.get(PREFIX + "/api/swarms/" + segment(input, "swarmId"));
            case SWARM_WAIT_READY -> readiness.observe(
                PREFIX + "/api/swarms/" + segment(input, "swarmId"), text(input, "swarmId"));
            case SWARM_CREATE -> owners.post(PREFIX + "/api/swarms/" + segment(input, "swarmId") + "/create",
                swarmCreateBody(input));
            case SWARM_START -> lifecycle(input, "start");
            case SWARM_STOP -> lifecycle(input, "stop");
            case SWARM_REMOVE -> lifecycle(input, "remove");
            case DEBUG_JOURNAL -> owners.get(PREFIX + "/api/swarms/" + segment(input, "swarmId")
                + "/journal/page?limit=" + queryOr(input, "limit",
                    McpToolDefaults.requireLimitFor(McpToolId.DEBUG_JOURNAL))
                + optionalSegmentQuery(input, "runId") + optionalQuery(input, "severity"));
            case DEBUG_JOURNAL_RUNS -> owners.get(PREFIX + "/api/swarms/"
                + segment(input, "swarmId") + "/journal/runs");
            case DEBUG_HIVE_JOURNAL -> owners.get(PREFIX + "/api/journal/hive/page?limit="
                + queryOr(input, "limit", McpToolDefaults.requireLimitFor(McpToolId.DEBUG_HIVE_JOURNAL)));
            case DEBUG_TAP -> owners.post(PREFIX + "/api/debug/taps", input);
            case DEBUG_TAP_READ -> owners.get(PREFIX + "/api/debug/taps/" + segment(input, "tapId")
                + (input.containsKey("drain") ? "?drain=" + query(input, "drain") : ""));
            case DEBUG_TAP_CLOSE -> owners.delete(PREFIX + "/api/debug/taps/" + segment(input, "tapId"));
            case COMPONENT_CONFIG_PREVIEW -> owners.post(PREFIX + "/api/components/"
                + segment(input, "role") + "/" + segment(input, "instanceId") + "/config/preview",
                body(input, "swarmId", "patch"));
            case COMPONENT_CONFIG_UPDATE -> owners.post(PREFIX + "/api/components/"
                + segment(input, "role") + "/" + segment(input, "instanceId") + "/config",
                body(input, "swarmId", "patch", "idempotencyKey"));
            case RUNTIME_CLEANUP_PLAN -> owners.post(PREFIX + "/api/runtime/cleanup/plan", cleanupPlanBody(input));
            case RUNTIME_CLEANUP_EXECUTE -> owners.post(PREFIX + "/api/runtime/cleanup/execute",
                cleanupExecuteBody(input));
            case RUNTIME_TAIL_WORKER_LOGS -> owners.post(PREFIX + "/api/runtime/debug/resources/logs", input);
            case RUNTIME_GET_WORKER_VERSION -> owners.post(PREFIX + "/api/runtime/debug/resources/version", input);
            case RUNTIME_LIST_WORKERS -> owners.post(PREFIX + "/api/runtime/debug/resources/list", input);
            case RUNTIME_INSPECT_WORKER -> owners.post(PREFIX + "/api/runtime/debug/resources/inspect", input);
            case RUNTIME_RABBIT_TOPOLOGY_SNAPSHOT -> owners.post(
                PREFIX + "/api/runtime/debug/rabbit/topology", input);
            case RUNTIME_ASSESS_SWARM, RUNTIME_DIFF_SWARM_RUNTIME, RUNTIME_CONTROL_PLANE_STATUS,
                 RUNTIME_MANIFEST_VALIDATE -> owners.post(PREFIX + "/api/runtime/debug/assessment",
                    body(input, "swarmId", "runId"));
            case RUNTIME_SWARM_TIMELINE -> owners.get(PREFIX + "/api/swarms/"
                + segment(input, "swarmId") + "/journal/page?limit="
                + queryOr(input, "limit",
                    McpToolDefaults.requireLimitFor(McpToolId.RUNTIME_SWARM_TIMELINE)));
            default -> throw new ToolExecutionException("TOOL_HANDLER_MISSING", toolId.externalName());
        };
    }

    private Object lifecycle(Map<String, Object> input, String action) {
        return owners.post(PREFIX + "/api/swarms/" + segment(input, "swarmId") + "/" + action,
            Map.of("idempotencyKey", text(input, "idempotencyKey")));
    }

    private static Map<String, Object> cleanupPlanBody(Map<String, Object> input) {
        booleanValue(input, "includeRunning");
        booleanValue(input, "includeRabbit");
        return body(input, "swarmId", "runId", "includeRunning", "includeRabbit");
    }

    private static Map<String, Object> cleanupExecuteBody(Map<String, Object> input) {
        booleanValue(input, "includeRunning");
        booleanValue(input, "includeRabbit");
        return body(input, "swarmId", "runId", "includeRunning", "includeRabbit",
            "candidateSetHash", "candidateIds", "idempotencyKey", "reason", "actor");
    }

    private static Map<String, Object> swarmCreateBody(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("templateId", text(input, "templateId"));
        result.put("idempotencyKey", text(input, "idempotencyKey"));
        result.put("autoPullImages", booleanValue(input, "autoPullImages"));
        result.put("sutId", requiredNullableText(input, "sutId"));
        result.put("variablesProfileId", requiredNullableText(input, "variablesProfileId"));
        result.put("networkMode", networkMode(input).name());
        result.put("networkProfileId", requiredNullableText(input, "networkProfileId"));
        return result;
    }

    private static NetworkMode networkMode(Map<String, Object> input) {
        try {
            return NetworkMode.valueOf(text(input, "networkMode"));
        } catch (IllegalArgumentException exception) {
            throw new ToolExecutionException("TOOL_INPUT_INVALID", "networkMode");
        }
    }
}
