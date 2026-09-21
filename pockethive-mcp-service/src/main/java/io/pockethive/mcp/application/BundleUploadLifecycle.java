package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.PrincipalKey;

/**
 * Responsibility: Define the closed bundle upload lifecycle application contract.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: RESP-MCP-UPLOAD-LIFECYCLE - docs/architecture/runtime-responsibilities.md#resp-mcp-upload-lifecycle.
 */

public interface BundleUploadLifecycle {
    void validated(PrincipalKey principal, UploadWorkflowBinding binding, String archiveDigest,
                   String bundleContentDigest, UploadCoordinationSnapshot uploadState);

    void requirePublicationCurrent(PrincipalKey principal, UploadWorkflowBinding binding);

    void published(PrincipalKey principal, UploadWorkflowBinding binding, PublicationAttempt attempt);
}
