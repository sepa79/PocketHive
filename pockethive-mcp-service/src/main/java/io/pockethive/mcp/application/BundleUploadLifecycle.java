package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.PrincipalKey;

/**
 * Responsibility: Define the closed bundle upload lifecycle application contract.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public interface BundleUploadLifecycle {
    void validated(PrincipalKey principal, String workflowId, String archiveDigest,
                   String bundleContentDigest);

    void published(PrincipalKey principal, String workflowId, PublicationAttempt attempt);
}
