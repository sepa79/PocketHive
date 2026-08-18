package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.PrincipalKey;
import java.time.Instant;

public record PublicationAttemptSnapshot(
    String id,
    PrincipalKey principal,
    PublicationMode mode,
    String scenarioId,
    String expectedContentDigest,
    Instant createdAt,
    PublicationAttemptState state,
    Object ownerResult
) {
}
