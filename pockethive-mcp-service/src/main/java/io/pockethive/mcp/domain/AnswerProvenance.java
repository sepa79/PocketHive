package io.pockethive.mcp.domain;

import java.time.Instant;

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
