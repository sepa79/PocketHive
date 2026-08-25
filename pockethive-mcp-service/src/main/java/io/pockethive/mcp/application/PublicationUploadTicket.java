package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.SourceMetadata;
import java.time.Instant;
import java.util.Objects;

public final class PublicationUploadTicket implements BundleUploadTicket {
    private final String id;
    private final PrincipalKey principal;
    private final UploadWorkflowBinding workflowBinding;
    private final SourceMetadata source;
    private final BundleFileManifest manifest;
    private final String uploadCapabilityDigest;
    private final Instant expiresAt;
    private final String attemptId;
    private final String validationReceiptId;
    private final String expectedArchiveDigest;
    private final String expectedContentDigest;
    private final PublicationMode mode;
    private final String scenarioId;
    private UploadTicketState state = UploadTicketState.PREPARED;

    public PublicationUploadTicket(String id, PrincipalKey principal, UploadWorkflowBinding workflowBinding,
                                   SourceMetadata source, BundleFileManifest manifest, String uploadCapabilityDigest,
                                   Instant expiresAt,
                                   String attemptId, String validationReceiptId, String expectedArchiveDigest,
                                   String expectedContentDigest, PublicationMode mode, String scenarioId) {
        this.id = Objects.requireNonNull(id);
        this.principal = Objects.requireNonNull(principal);
        this.workflowBinding = Objects.requireNonNull(workflowBinding);
        this.source = Objects.requireNonNull(source);
        this.manifest = Objects.requireNonNull(manifest);
        this.uploadCapabilityDigest = UploadCapabilityAuthority.requireDigest(uploadCapabilityDigest);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.attemptId = Objects.requireNonNull(attemptId);
        this.validationReceiptId = Objects.requireNonNull(validationReceiptId);
        this.expectedArchiveDigest = Objects.requireNonNull(expectedArchiveDigest);
        this.expectedContentDigest = Objects.requireNonNull(expectedContentDigest);
        this.mode = Objects.requireNonNull(mode);
        if (mode == PublicationMode.REPLACE && (scenarioId == null || scenarioId.isBlank())) {
            throw new IllegalArgumentException("PUBLICATION_SCENARIO_ID_REQUIRED");
        }
        if (mode == PublicationMode.CREATE && scenarioId != null) {
            throw new IllegalArgumentException("PUBLICATION_SCENARIO_ID_FORBIDDEN");
        }
        this.scenarioId = scenarioId;
    }

    static PublicationUploadTicket restore(UploadTicketSnapshot snapshot) {
        PublicationUploadTicket ticket = new PublicationUploadTicket(snapshot.id(), snapshot.principal(),
            snapshot.workflowBinding(), snapshot.source(), snapshot.manifest(), snapshot.uploadCapabilityDigest(),
            snapshot.expiresAt(),
            snapshot.attemptId(), snapshot.validationReceiptId(), snapshot.expectedArchiveDigest(),
            snapshot.expectedContentDigest(), snapshot.publicationMode(), snapshot.scenarioId());
        ticket.state = snapshot.state();
        return ticket;
    }

    @Override public String id() { return id; }
    @Override public PrincipalKey principal() { return principal; }
    @Override public UploadWorkflowBinding workflowBinding() { return workflowBinding; }
    @Override public SourceMetadata source() { return source; }
    @Override public BundleFileManifest manifest() { return manifest; }
    @Override public String uploadCapabilityDigest() { return uploadCapabilityDigest; }
    @Override public Instant expiresAt() { return expiresAt; }
    @Override public UploadTicketState state() { return state; }
    public String attemptId() { return attemptId; }
    public String validationReceiptId() { return validationReceiptId; }
    public String expectedArchiveDigest() { return expectedArchiveDigest; }
    public String expectedContentDigest() { return expectedContentDigest; }
    public PublicationMode mode() { return mode; }
    public String scenarioId() { return scenarioId; }
    @Override public void begin() { transition(UploadTicketState.PREPARED, UploadTicketState.RECEIVING); }
    @Override public void fail() { transition(UploadTicketState.RECEIVING, UploadTicketState.FAILED); }
    @Override public void consume() { transition(UploadTicketState.RECEIVING, UploadTicketState.CONSUMED); }

    private void transition(UploadTicketState expected, UploadTicketState next) {
        if (state != expected) {
            throw new UploadRejectedException("UPLOAD_TICKET_CONSUMED");
        }
        state = next;
    }
}
