package io.pockethive.mcp.application;

import java.time.Instant;

/**
 * Responsibility: Carry immutable publication attempt view application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

/** Principal-safe client projection of publication progress and owner evidence. */
public record PublicationAttemptView(
    String attemptId,
    PublicationMode mode,
    String scenarioId,
    String expectedContentDigest,
    Instant createdAt,
    PublicationAttemptState state,
    Object ownerResult
) {
    public static PublicationAttemptView from(PublicationAttempt attempt) {
        return new PublicationAttemptView(attempt.id(), attempt.mode(), attempt.scenarioId(),
            attempt.expectedContentDigest(), attempt.createdAt(), attempt.state(), attempt.ownerResult());
    }
}
