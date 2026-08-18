package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.SourceMetadata;
import java.time.Instant;

public record UploadTicketSnapshot(
    UploadTicketPurpose purpose,
    String id,
    PrincipalKey principal,
    UploadWorkflowBinding workflowBinding,
    SourceMetadata source,
    BundleFileManifest manifest,
    Instant expiresAt,
    UploadTicketState state,
    String attemptId,
    String validationReceiptId,
    String expectedArchiveDigest,
    String expectedContentDigest,
    PublicationMode publicationMode,
    String scenarioId
) {
    public static UploadTicketSnapshot from(BundleUploadTicket ticket) {
        if (ticket instanceof PublicationUploadTicket publication) {
            return new UploadTicketSnapshot(UploadTicketPurpose.PUBLICATION, ticket.id(), ticket.principal(),
                ticket.workflowBinding(), ticket.source(), ticket.manifest(), ticket.expiresAt(), ticket.state(),
                publication.attemptId(), publication.validationReceiptId(), publication.expectedArchiveDigest(),
                publication.expectedContentDigest(), publication.mode(), publication.scenarioId());
        }
        return new UploadTicketSnapshot(UploadTicketPurpose.VALIDATION, ticket.id(), ticket.principal(),
            ticket.workflowBinding(), ticket.source(), ticket.manifest(), ticket.expiresAt(), ticket.state(),
            null, null, null, null, null, null);
    }

    public BundleUploadTicket restore() {
        return switch (purpose) {
            case VALIDATION -> ValidationUploadTicket.restore(this);
            case PUBLICATION -> PublicationUploadTicket.restore(this);
        };
    }
}
