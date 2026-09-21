package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.SourceMetadata;
import java.time.Instant;
import java.util.Objects;

/**
 * Responsibility: Carry immutable bundle validation receipt application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public record BundleValidationReceipt(
    String id,
    PrincipalKey principal,
    UploadWorkflowBinding workflowBinding,
    SourceMetadata source,
    BundleFileManifest manifest,
    String archiveDigest,
    String bundleContentDigest,
    String scenarioId,
    String scenarioName,
    Instant createdAt
) {
    public BundleValidationReceipt {
        Objects.requireNonNull(id);
        Objects.requireNonNull(principal);
        Objects.requireNonNull(workflowBinding);
        Objects.requireNonNull(source);
        Objects.requireNonNull(manifest);
        Objects.requireNonNull(archiveDigest);
        Objects.requireNonNull(bundleContentDigest);
        Objects.requireNonNull(scenarioId);
        Objects.requireNonNull(scenarioName);
        Objects.requireNonNull(createdAt);
    }
}
