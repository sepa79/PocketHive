package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.ElicitationAction;
import java.util.Map;

/**
 * Responsibility: carry the explicit client elicitation action and answer content. Must not:
 * approve workflow transitions or interpret answers. Contract: RESP-MCP-CLIENT-INTERACTION —
 * docs/architecture/runtime-responsibilities.md#resp-mcp-client-interaction.
 */
public record ClientElicitationResult(ElicitationAction action, Map<String, Object> content) {}
