package io.pockethive.mcp.application;

import static io.pockethive.mcp.application.ToolArguments.booleanValue;
import static io.pockethive.mcp.application.ToolArguments.query;
import static io.pockethive.mcp.application.ToolArguments.segment;

import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Map Scenario Manager-owned MCP tools to the Scenario Manager public-ingress API.
 * Must not: Reimplement bundle validation, authoring contracts, or publication behavior.
 * Contract: docs/mcp/README.md.
 */
@Component
final class ScenarioManagerToolExecutor {
    private static final String PREFIX = "/scenario-manager";

    private final OwnerApiPort owners;

    ScenarioManagerToolExecutor(OwnerApiPort owners) {
        this.owners = owners;
    }

    boolean supports(McpToolId toolId) {
        return toolId.owner() == ToolOwner.SCENARIO_MANAGER;
    }

    Object execute(McpToolId toolId, Map<String, Object> input) {
        return switch (toolId) {
            case SCENARIO_LIST -> owners.get(PREFIX + "/scenarios");
            case SCENARIO_GET -> owners.get(PREFIX + "/scenarios/" + segment(input, "scenarioId"));
            case SCENARIO_RAW_READ -> owners.getText(
                PREFIX + "/scenarios/" + segment(input, "scenarioId") + "/raw");
            case SCENARIO_SCHEMA_READ -> owners.getText(PREFIX + "/scenarios/" + segment(input, "scenarioId")
                + "/schema?path=" + query(input, "path"));
            case SCENARIO_TEMPLATE_READ -> owners.getText(
                PREFIX + "/scenarios/" + segment(input, "scenarioId")
                + "/template?path=" + query(input, "path"));
            case SCENARIO_BUNDLE_TREE_READ -> owners.get(PREFIX + "/scenarios/bundles/tree?bundleKey="
                + query(input, "bundleKey"));
            case SCENARIO_BUNDLE_FILE_READ -> owners.get(PREFIX + "/scenarios/bundles/file?bundleKey="
                + query(input, "bundleKey") + "&path=" + query(input, "path"));
            case SCENARIO_SUTS_LIST -> owners.get(
                PREFIX + "/scenarios/" + segment(input, "scenarioId") + "/suts");
            case SCENARIO_SUT_GET -> owners.get(PREFIX + "/scenarios/" + segment(input, "scenarioId")
                + "/suts/" + segment(input, "sutId"));
            case SCENARIO_CONTRACTS_GET -> Map.of(
                "authoringContract", owners.get(PREFIX + "/api/authoring-contract"),
                "fingerprint", owners.get(PREFIX + "/api/authoring-contract/fingerprint"));
            case SCENARIO_CAPABILITIES_GET -> owners.get(capabilitiesPath(input));
            case SCENARIO_TEMPLATES_CATALOG -> owners.get(PREFIX + "/api/templates");
            default -> throw new ToolExecutionException("TOOL_HANDLER_MISSING", toolId.externalName());
        };
    }

    private static String capabilitiesPath(Map<String, Object> input) {
        Set<String> selectors = Set.of("all", "imageName", "imageDigest");
        if (input.keySet().stream().anyMatch(key -> !selectors.contains(key))) {
            throw new ToolExecutionException("TOOL_INPUT_INVALID", "scenario capability selector");
        }
        long selected = selectors.stream().filter(input::containsKey).count();
        if (selected > 1) {
            throw new ToolExecutionException("TOOL_INPUT_INVALID", "select exactly one scenario capability selector");
        }
        if (input.containsKey("all")) {
            if (!booleanValue(input, "all")) {
                throw new ToolExecutionException("TOOL_INPUT_INVALID", "all must be true");
            }
            return PREFIX + "/api/capabilities?all=true";
        }
        if (input.containsKey("imageName")) {
            return PREFIX + "/api/capabilities?imageName=" + query(input, "imageName");
        }
        if (input.containsKey("imageDigest")) {
            return PREFIX + "/api/capabilities?imageDigest=" + query(input, "imageDigest");
        }
        return PREFIX + "/api/capabilities?all=true";
    }
}
