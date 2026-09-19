package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.PrincipalKey;
import java.time.Instant;

/**
 * Responsibility: Carry immutable publication attempt snapshot application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

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
