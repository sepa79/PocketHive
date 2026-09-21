package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.AgentSession;
import io.pockethive.mcp.domain.ScenarioWorkflow;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Construct the canonical MCP read-only projections of agent sessions and QA workflows.
 * Must not: Validate access, mutate state, execute workflows, or persist data.
 * Contract: docs/mcp/README.md.
 */
@Component
final class WorkflowProjection {
    Map<String, Object> session(AgentSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agentSessionId", session.id());
        result.put("state", session.state());
        result.put("revision", session.revision());
        result.put("createdAt", session.createdAt());
        result.put("expiresAt", session.expiresAt());
        result.put("closedAt", session.closedAt());
        result.put("workflowIds", session.workflowIds());
        return result;
    }

    Map<String, Object> workflow(ScenarioWorkflow workflow) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowId", workflow.id());
        result.put("agentSessionId", workflow.agentSessionId());
        result.put("state", workflow.state());
        result.put("revision", workflow.revision());
        result.put("requirements", workflow.requirements());
        result.put("capabilityFingerprint", workflow.capabilityFingerprint());
        result.put("generatedFileSetDigest", workflow.generatedFileSetDigest());
        result.put("validation", workflow.validation());
        result.put("publicationReceiptDigest", workflow.publicationReceiptDigest());
        return result;
    }
}
