package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.SourceMetadata;
import java.time.Instant;
import java.util.Objects;

/**
 * Responsibility: Own the validation upload ticket state machine and bound validation intent.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public final class ValidationUploadTicket implements BundleUploadTicket {
    private final String id;
    private final PrincipalKey principal;
    private final UploadWorkflowBinding workflowBinding;
    private final SourceMetadata source;
    private final BundleFileManifest manifest;
    private final String uploadCapabilityDigest;
    private final Instant expiresAt;
    private UploadTicketState state = UploadTicketState.PREPARED;

    public ValidationUploadTicket(String id, PrincipalKey principal, UploadWorkflowBinding workflowBinding,
                                  SourceMetadata source, BundleFileManifest manifest,
                                  String uploadCapabilityDigest, Instant expiresAt) {
        this.id = Objects.requireNonNull(id);
        this.principal = Objects.requireNonNull(principal);
        this.workflowBinding = Objects.requireNonNull(workflowBinding);
        this.source = Objects.requireNonNull(source);
        this.manifest = Objects.requireNonNull(manifest);
        this.uploadCapabilityDigest = UploadCapabilityAuthority.requireDigest(uploadCapabilityDigest);
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    static ValidationUploadTicket restore(UploadTicketSnapshot snapshot) {
        ValidationUploadTicket ticket = new ValidationUploadTicket(snapshot.id(), snapshot.principal(),
            snapshot.workflowBinding(), snapshot.source(), snapshot.manifest(), snapshot.uploadCapabilityDigest(),
            snapshot.expiresAt());
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
