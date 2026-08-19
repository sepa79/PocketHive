package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.BundleFileManifestEntry;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.SourceMetadata;
import io.pockethive.mcp.domain.SourceVerification;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UploadStateMachineTest {
    private static final PrincipalKey PRINCIPAL =
        new PrincipalKey(URI.create("https://issuer.example"), "qa-lead");
    private static final Instant NOW = Instant.parse("2026-08-18T12:00:00Z");
    private static final String SHA = "sha256:" + "a".repeat(64);
    private static final SourceMetadata SOURCE = new SourceMetadata(
        "https://git.example/repo", "a".repeat(40), "scenarios/sample", SourceVerification.CLIENT_ASSERTED);
    private static final BundleFileManifest MANIFEST = new BundleFileManifest(List.of(
        new BundleFileManifestEntry("scenario.yaml", 4, SHA)));

    @Test
    void validationTicketPreservesIdentityAndSupportsOnlyOneTerminalTransition() {
        ValidationUploadTicket consumed = validation("validation-a");
        assertValidationFields(consumed, "validation-a");
        assertThat(consumed.uploadPath()).isEqualTo("/mcp/uploads/validation-a");
        consumed.begin();
        consumed.consume();
        assertThat(consumed.state()).isEqualTo(UploadTicketState.CONSUMED);
        assertThatThrownBy(consumed::consume).isInstanceOf(UploadRejectedException.class)
            .hasMessage("UPLOAD_TICKET_CONSUMED");

        ValidationUploadTicket failed = validation("validation-b");
        failed.begin();
        failed.fail();
        assertThat(failed.state()).isEqualTo(UploadTicketState.FAILED);
        assertThatThrownBy(failed::begin).isInstanceOf(UploadRejectedException.class)
            .hasMessage("UPLOAD_TICKET_CONSUMED");
    }

    @Test
    void publicationTicketPreservesEveryBoundIntentAndSupportsFailureAndConsumption() {
        PublicationUploadTicket failed = publication("publication-a", PublicationMode.REPLACE, "scenario-a");
        assertPublicationFields(failed, "publication-a", PublicationMode.REPLACE, "scenario-a");
        failed.begin();
        failed.fail();
        assertThat(failed.state()).isEqualTo(UploadTicketState.FAILED);

        PublicationUploadTicket consumed = publication("publication-b", PublicationMode.CREATE, null);
        assertPublicationFields(consumed, "publication-b", PublicationMode.CREATE, null);
        consumed.begin();
        consumed.consume();
        assertThat(consumed.state()).isEqualTo(UploadTicketState.CONSUMED);
    }

    @Test
    void publicationAttemptSupportsSuccessFailureAmbiguityAndExactRestoration() {
        PublicationAttempt success = attempt("attempt-success");
        assertAttemptFields(success, "attempt-success");
        success.receiving();
        success.verified();
        success.ownerCallInFlight();
        success.succeeded(Map.of("receipt", "owner-a"));
        assertThat(success.state()).isEqualTo(PublicationAttemptState.SUCCEEDED);
        assertThat(success.ownerResult()).isEqualTo(Map.of("receipt", "owner-a"));
        assertThat(PublicationAttempt.restore(success.snapshot()).snapshot()).isEqualTo(success.snapshot());

        for (PublicationAttemptState failureSource : List.of(
            PublicationAttemptState.RECEIVING,
            PublicationAttemptState.VERIFIED,
            PublicationAttemptState.OWNER_CALL_IN_FLIGHT)) {
            PublicationAttempt failed = attempt("failed-" + failureSource.name());
            failed.receiving();
            if (failureSource != PublicationAttemptState.RECEIVING) {
                failed.verified();
            }
            if (failureSource == PublicationAttemptState.OWNER_CALL_IN_FLIGHT) {
                failed.ownerCallInFlight();
            }
            failed.failed();
            assertThat(failed.state()).isEqualTo(PublicationAttemptState.FAILED);
        }

        PublicationAttempt ambiguous = attempt("attempt-ambiguous");
        ambiguous.receiving();
        ambiguous.verified();
        ambiguous.ownerCallInFlight();
        ambiguous.ambiguous();
        ambiguous.succeeded(Map.of("receipt", "reconciled"));
        assertThat(ambiguous.ownerResult()).isEqualTo(Map.of("receipt", "reconciled"));
    }

    @Test
    void invalidBindingsPublicationIntentAndTransitionsFailExplicitly() {
        assertThatThrownBy(() -> new UploadWorkflowBinding(null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("UPLOAD_WORKFLOW_MODE_REQUIRED");
        assertThatThrownBy(() -> new UploadWorkflowBinding(UploadWorkflowMode.WORKFLOW, " "))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("UPLOAD_WORKFLOW_ID_REQUIRED");
        assertThatThrownBy(() -> new UploadWorkflowBinding(UploadWorkflowMode.DIRECT, "workflow"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("UPLOAD_WORKFLOW_ID_FORBIDDEN");
        assertThatThrownBy(() -> publication("replace-missing", PublicationMode.REPLACE, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("PUBLICATION_SCENARIO_ID_REQUIRED");
        assertThatThrownBy(() -> publication("create-specified", PublicationMode.CREATE, "scenario"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("PUBLICATION_SCENARIO_ID_FORBIDDEN");

        PublicationUploadTicket ticket = publication("used", PublicationMode.CREATE, null);
        ticket.begin();
        ticket.consume();
        assertThatThrownBy(ticket::consume).isInstanceOf(UploadRejectedException.class)
            .hasMessage("UPLOAD_TICKET_CONSUMED");

        PublicationAttempt attempt = attempt("invalid");
        assertThatThrownBy(() -> attempt.succeeded(Map.of()))
            .isInstanceOf(IllegalStateException.class).hasMessage("PUBLICATION_ATTEMPT_TRANSITION_INVALID");
        assertThatThrownBy(attempt::failed)
            .isInstanceOf(IllegalStateException.class).hasMessage("PUBLICATION_ATTEMPT_TRANSITION_INVALID");
        assertThatThrownBy(attempt::verified)
            .isInstanceOf(IllegalStateException.class).hasMessage("PUBLICATION_ATTEMPT_TRANSITION_INVALID");
    }

    @Test
    void uploadSnapshotsRoundTripBothTicketTypes() {
        ValidationUploadTicket validation = validation("validation");
        validation.begin();
        validation.consume();
        BundleUploadTicket restoredValidation = UploadTicketSnapshot.from(validation).restore();
        assertThat(UploadTicketSnapshot.from(restoredValidation)).isEqualTo(UploadTicketSnapshot.from(validation));

        PublicationUploadTicket publication = publication("publication", PublicationMode.REPLACE, "scenario-a");
        publication.begin();
        publication.fail();
        BundleUploadTicket restoredPublication = UploadTicketSnapshot.from(publication).restore();
        assertThat(UploadTicketSnapshot.from(restoredPublication)).isEqualTo(UploadTicketSnapshot.from(publication));
    }

    @Test
    void uploadCoordinationSnapshotHasImmutableRecordValueSemantics() {
        UploadCoordinationSnapshot snapshot = UploadCoordinationSnapshot.empty();
        UploadCoordinationSnapshot equivalent = new UploadCoordinationSnapshot(Map.of(), Map.of(), Map.of());

        assertThat(snapshot).isEqualTo(equivalent).hasSameHashCodeAs(equivalent);
        assertThat(snapshot.tickets()).isEmpty();
        assertThat(snapshot.receipts()).isEmpty();
        assertThat(snapshot.attempts()).isEmpty();
        assertThat(snapshot.toString()).contains("tickets", "receipts", "attempts");
    }

    private static void assertValidationFields(ValidationUploadTicket ticket, String id) {
        assertThat(ticket.id()).isEqualTo(id);
        assertThat(ticket.principal()).isEqualTo(PRINCIPAL);
        assertThat(ticket.workflowBinding()).isEqualTo(UploadWorkflowBinding.direct());
        assertThat(ticket.source()).isEqualTo(SOURCE);
        assertThat(ticket.manifest()).isEqualTo(MANIFEST);
        assertThat(ticket.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(ticket.state()).isEqualTo(UploadTicketState.PREPARED);
    }

    private static void assertPublicationFields(PublicationUploadTicket ticket, String id,
                                                PublicationMode mode, String scenarioId) {
        assertThat(ticket.id()).isEqualTo(id);
        assertThat(ticket.principal()).isEqualTo(PRINCIPAL);
        assertThat(ticket.workflowBinding()).isEqualTo(UploadWorkflowBinding.direct());
        assertThat(ticket.source()).isEqualTo(SOURCE);
        assertThat(ticket.manifest()).isEqualTo(MANIFEST);
        assertThat(ticket.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(ticket.attemptId()).isEqualTo("attempt-a");
        assertThat(ticket.validationReceiptId()).isEqualTo("receipt-a");
        assertThat(ticket.expectedArchiveDigest()).isEqualTo(SHA);
        assertThat(ticket.expectedContentDigest()).isEqualTo(SHA);
        assertThat(ticket.mode()).isEqualTo(mode);
        assertThat(ticket.scenarioId()).isEqualTo(scenarioId);
        assertThat(ticket.state()).isEqualTo(UploadTicketState.PREPARED);
    }

    private static void assertAttemptFields(PublicationAttempt attempt, String id) {
        assertThat(attempt.id()).isEqualTo(id);
        assertThat(attempt.principal()).isEqualTo(PRINCIPAL);
        assertThat(attempt.mode()).isEqualTo(PublicationMode.REPLACE);
        assertThat(attempt.scenarioId()).isEqualTo("scenario-a");
        assertThat(attempt.expectedContentDigest()).isEqualTo(SHA);
        assertThat(attempt.createdAt()).isEqualTo(NOW);
        assertThat(attempt.state()).isEqualTo(PublicationAttemptState.PREPARED);
        assertThat(attempt.ownerResult()).isNull();
    }

    private static ValidationUploadTicket validation(String id) {
        return new ValidationUploadTicket(id, PRINCIPAL, UploadWorkflowBinding.direct(), SOURCE, MANIFEST,
            NOW.plusSeconds(60));
    }

    private static PublicationUploadTicket publication(String id, PublicationMode mode, String scenarioId) {
        return new PublicationUploadTicket(id, PRINCIPAL, UploadWorkflowBinding.direct(), SOURCE, MANIFEST,
            NOW.plusSeconds(60), "attempt-a", "receipt-a", SHA, SHA, mode, scenarioId);
    }

    private static PublicationAttempt attempt(String id) {
        return new PublicationAttempt(id, PRINCIPAL, PublicationMode.REPLACE, "scenario-a", SHA, NOW);
    }
}
