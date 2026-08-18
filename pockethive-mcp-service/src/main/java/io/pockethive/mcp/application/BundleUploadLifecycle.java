package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.PrincipalKey;

public interface BundleUploadLifecycle {
    void validated(PrincipalKey principal, String workflowId, String archiveDigest,
                   String bundleContentDigest);

    void published(PrincipalKey principal, String workflowId, PublicationAttempt attempt);
}
