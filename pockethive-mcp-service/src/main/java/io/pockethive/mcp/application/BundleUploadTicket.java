package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.SourceMetadata;
import java.time.Instant;

/**
 * Responsibility: Define the closed bundle upload ticket application contract.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public sealed interface BundleUploadTicket permits ValidationUploadTicket, PublicationUploadTicket {
    String id();
    PrincipalKey principal();
    UploadWorkflowBinding workflowBinding();
    SourceMetadata source();
    BundleFileManifest manifest();
    String uploadCapabilityDigest();
    Instant expiresAt();
    UploadTicketState state();
    void begin();
    void fail();
    void consume();

    default String uploadPath() {
        return BundleUploadContract.PATH_PREFIX + id();
    }
}
