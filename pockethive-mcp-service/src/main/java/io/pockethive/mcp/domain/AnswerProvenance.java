package io.pockethive.mcp.domain;

import java.time.Instant;

/**
 * Responsibility: Model the AnswerProvenance MCP domain concept and enforce its local invariants.
 * Must not: Access transport, configuration, or infrastructure adapters.
 * Contract: docs/mcp/README.md.
 */

public record AnswerProvenance(
    PrincipalKey principal,
    String oauthClientId,
    String declaredClientName,
    String declaredClientVersion,
    String workflowId,
    long workflowRevision,
    String questionId,
    String requestedSchemaDigest,
    ElicitationAction action,
    String acceptedContentDigest,
    Instant observedAt
) {
}
