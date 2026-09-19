package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.mcp.adapter.persistence.AtomicCoordinationStateRepository;
import io.pockethive.mcp.config.McpStateMode;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import io.pockethive.mcp.domain.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowBoundUploadTest {
    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final PrincipalKey PRINCIPAL = new PrincipalKey(URI.create("https://issuer.example"), "qa-lead");
    private static final SourceMetadata SOURCE = new SourceMetadata("https://git.example/scenarios",
        "a".repeat(40), "scenarios/a", SourceVerification.CLIENT_ASSERTED);
    @TempDir Path temporaryDirectory;
    private ObjectMapper mapper;
    private AtomicCoordinationStateRepository state;
    private ScenarioBundleOwnerPort owner;
    private BundleUploadCoordinator uploads;
    private BundleFileManifest manifest;
    private byte[] archive;

    @BeforeEach
    void setUp() throws Exception {
        mapper = org.mockito.Mockito.spy(new ObjectMapper().findAndRegisterModules());
        state = new AtomicCoordinationStateRepository(mapper, McpStateMode.MEMORY,
            temporaryDirectory.resolve("coordination"), 1_000_000, 10, 5);
        AgentSession session = AgentSession.open("as-1", PRINCIPAL, NOW, Duration.ofHours(1));
        ScenarioWorkflow workflow = ScenarioWorkflow.create("wf-1", session.id(), PRINCIPAL);
        session.addWorkflow(0, workflow.id(), 4);
        state.createSession(session);
        state.createWorkflow(session, workflow);
        for (QaRequirementTopic topic : QaRequirementTopic.values()) {
            long revision = workflow.revision();
            AnswerProvenance provenance = new AnswerProvenance(PRINCIPAL, "vscode", "PocketHive", "1",
                workflow.id(), revision, topic.name(), "sha256:schema", ElicitationAction.ACCEPT,
                "sha256:content", NOW);
            workflow.answer(revision, topic, RequirementAnswer.notApplicable("Not needed", provenance));
        }
        workflow.readyToGenerate(workflow.revision(), new CapabilityFingerprint("sha256:capabilities-A", NOW));
        workflow.generated(workflow.revision(), "sha256:files-A");
        state.saveWorkflow(workflow, 0, List.of());
        owner = mock(ScenarioBundleOwnerPort.class);
        when(owner.validate(any())).thenReturn(validated());
        when(owner.create(any())).thenReturn(Map.of("id", "scenario-a"));
        PocketHiveMcpProperties properties = properties();
        uploads = new BundleUploadCoordinator(owner, properties, state,
            new CoordinationWorkflowUploadLifecycle(state, mapper,
                new WorkflowAccess(state, properties, Clock.fixed(NOW, java.time.ZoneOffset.UTC))));
        byte[] file = "id: scenario-a\n".getBytes(StandardCharsets.UTF_8);
        manifest = new BundleFileManifest(List.of(BundleFileManifestEntry.fromBytes("scenario.yaml", file)));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("scenario.yaml"));
            zip.write(file);
            zip.closeEntry();
        }
        archive = bytes.toByteArray();
    }

    @Test
    void currentGenerationValidatesAndPublishesWithThePreparedIdentity() {
        ScenarioWorkflow generated = workflow();
        ValidationUploadTicket ticket = prepareValidation();
        BundleValidationReceipt receipt = validate(ticket);
        assertThat(receipt.workflowBinding()).isEqualTo(UploadWorkflowBinding.workflow(generated));
        assertThat(workflow().revision()).isEqualTo(generated.revision() + 1);
        assertThat(workflow().state()).isEqualTo(ScenarioWorkflowState.VALIDATED);
        PublicationUploadTicket publication = preparePublication(receipt);
        PublicationUploadOutcome result = (PublicationUploadOutcome) receive(publication);
        assertThat(result.publicationAttempt().state()).isEqualTo(PublicationAttemptState.SUCCEEDED);
        assertThat(workflow().state()).isEqualTo(ScenarioWorkflowState.PUBLISHED);
        assertThat(workflow().revision()).isEqualTo(generated.revision() + 2);
        assertThat(workflow().generatedFileSetDigest()).isEqualTo("sha256:files-A");
    }

    @Test
    void rejectsValidationFromSupersededGeneratedFilesWithoutRetainingAnAcceptedReceipt() {
        ValidationUploadTicket ticket = prepareValidation();
        regenerate();
        assertStaleValidation(ticket);
    }

    @Test
    void rejectsMismatchedPreparedFilesOrCapabilitiesEvenWhenRevisionMatches() {
        ScenarioWorkflow generated = workflow();
        for (UploadWorkflowBinding mismatched : List.of(
            new UploadWorkflowBinding(UploadWorkflowMode.WORKFLOW, generated.id(), generated.revision(),
                "sha256:wrong-files", generated.capabilityFingerprint()),
            new UploadWorkflowBinding(UploadWorkflowMode.WORKFLOW, generated.id(), generated.revision(),
                generated.generatedFileSetDigest(), new CapabilityFingerprint("sha256:wrong-capabilities", NOW)))) {
            ValidationUploadTicket ticket = uploads.prepareValidation(PRINCIPAL, mismatched, SOURCE, manifest, NOW);
            assertThatThrownBy(() -> receive(ticket)).isInstanceOf(UploadRejectedException.class)
                .hasRootCauseMessage("WORKFLOW_GENERATION_MISMATCH");
            assertThat(workflow().snapshot()).isEqualTo(generated.snapshot());
            assertThat(state.loadUploadCoordination().receipts()).isEmpty();
            assertThat(state.loadUploadCoordination().tickets().get(ticket.id()).state())
                .isEqualTo(UploadTicketState.FAILED);
        }
    }

    @Test
    void rejectsValidationWhenGenerationChangesDuringOwnerValidation() {
        ValidationUploadTicket ticket = prepareValidation();
        when(owner.validate(any())).thenAnswer(invocation -> {
            regenerate();
            return validated();
        });
        assertStaleValidation(ticket);
    }

    @Test
    void regenerationClearsPriorValidationFromTheCurrentWorkflowProjection() {
        validate(prepareValidation());
        assertThat(workflow().validation()).isNotNull();
        regenerate();
        ScenarioWorkflow replacement = workflow();
        assertThat(replacement.state()).isEqualTo(ScenarioWorkflowState.GENERATED);
        assertThat(replacement.generatedFileSetDigest()).isEqualTo("sha256:files-B");
        assertThat(replacement.validation()).isNull();
        assertThat(replacement.publicationReceiptDigest()).isNull();
        assertThat(replacement.snapshot().validation()).isNull();
    }

    @Test
    void staleReceiptCannotPreparePublication() {
        BundleValidationReceipt receipt = validate(prepareValidation());
        regenerate();
        assertThatThrownBy(() -> preparePublication(receipt)).hasMessage("WORKFLOW_VERSION_CONFLICT");
        assertThat(state.loadUploadCoordination().attempts()).isEmpty();
        verify(owner, never()).create(any());
    }

    @Test
    void preparedPublicationCannotPublishARegeneratedAndValidatedWorkflow() {
        BundleValidationReceipt receiptA = validate(prepareValidation());
        PublicationUploadTicket publicationA = preparePublication(receiptA);
        regenerate();
        validate(prepareValidation());
        long revisionB = workflow().revision();
        assertThatThrownBy(() -> receive(publicationA)).isInstanceOf(UploadRejectedException.class)
            .hasRootCauseMessage("WORKFLOW_VERSION_CONFLICT");
        assertThat(workflow().revision()).isEqualTo(revisionB);
        assertThat(workflow().state()).isEqualTo(ScenarioWorkflowState.VALIDATED);
        assertThat(workflow().generatedFileSetDigest()).isEqualTo("sha256:files-B");
        assertThat(uploads.publicationAttempt(publicationA.attemptId(), PRINCIPAL).state())
            .isEqualTo(PublicationAttemptState.FAILED);
        verify(owner, never()).create(any());
    }

    @Test
    void ownerSuccessDuringRegenerationRemainsVisibleWithoutPublishingReplacementWorkflow() {
        PublicationUploadTicket publication = preparePublication(validate(prepareValidation()));
        when(owner.create(any())).thenAnswer(invocation -> {
            regenerate();
            return Map.of("id", "scenario-a");
        });
        assertThatThrownBy(() -> receive(publication)).isInstanceOf(PublicationStateSyncException.class)
            .hasMessage("PUBLICATION_SUCCEEDED_WORKFLOW_SYNC_FAILED:" + publication.attemptId())
            .hasRootCauseMessage("WORKFLOW_VERSION_CONFLICT");
        PublicationAttempt attempt = uploads.publicationAttempt(publication.attemptId(), PRINCIPAL);
        assertThat(attempt.state()).isEqualTo(PublicationAttemptState.SUCCEEDED);
        assertThat(attempt.ownerResult()).isEqualTo(Map.of("id", "scenario-a"));
        assertThat(workflow().state()).isEqualTo(ScenarioWorkflowState.GENERATED);
        assertThat(workflow().publicationReceiptDigest()).isNull();
        assertThat(workflow().generatedFileSetDigest()).isEqualTo("sha256:files-B");
        assertThatThrownBy(() -> receive(publication)).hasMessage("UPLOAD_TICKET_CONSUMED");
        verify(owner).create(any());
    }

    @Test
    void compareAndSaveRejectsACompletionSnapshotLoadedBeforeAConcurrentEdit() {
        ScenarioWorkflow completion = workflow();
        long preparedRevision = completion.revision();
        completion.validated(preparedRevision, "sha256:archive", "sha256:content-A");
        regenerate();
        assertThatThrownBy(() -> state.saveWorkflow(completion, preparedRevision))
            .isInstanceOf(ToolExecutionException.class)
            .extracting(error -> ((ToolExecutionException) error).code()).isEqualTo("WORKFLOW_VERSION_CONFLICT");
        assertThat(workflow().state()).isEqualTo(ScenarioWorkflowState.GENERATED);
        assertThat(workflow().generatedFileSetDigest()).isEqualTo("sha256:files-B");
        assertThat(workflow().validation()).isNull();
    }

    @Test
    void failedAtomicReceiptWriteLeavesGenerationUnvalidatedAndAllowsFreshValidation() throws Exception {
        ValidationUploadTicket ticket = prepareValidation();
        long revision = workflow().revision();
        var failOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            com.fasterxml.jackson.databind.JsonNode encoded = mapper.valueToTree(invocation.getArgument(0));
            if (!encoded.path("uploadCoordination").path("receipts").isEmpty() && failOnce.compareAndSet(true, false)) {
                throw new com.fasterxml.jackson.core.JsonProcessingException("injected atomic write failure") { };
            }
            return invocation.callRealMethod();
        }).when(mapper).writeValueAsBytes(any());
        assertThatThrownBy(() -> receive(ticket)).isInstanceOf(UploadRejectedException.class)
            .hasRootCauseMessage("injected atomic write failure");
        assertThat(workflow().revision()).isEqualTo(revision);
        assertThat(workflow().state()).isEqualTo(ScenarioWorkflowState.GENERATED);
        assertThat(workflow().validation()).isNull();
        assertThat(state.loadUploadCoordination().receipts()).isEmpty();
        assertThat(state.loadUploadCoordination().tickets().get(ticket.id()).state()).isEqualTo(UploadTicketState.FAILED);
        BundleValidationReceipt receipt = validate(prepareValidation());
        assertThat(workflow().state()).isEqualTo(ScenarioWorkflowState.VALIDATED);
        assertThat(state.loadUploadCoordination().receipts()).containsEntry(receipt.id(), receipt);
    }

    @Test
    void historicalUnboundReceiptsRemainReadableButCannotPrepareNewTickets() {
        UploadWorkflowBinding legacy = new UploadWorkflowBinding(UploadWorkflowMode.LEGACY_WORKFLOW, "wf-1", 0, null, null);
        BundleValidationReceipt receipt = new BundleValidationReceipt("vr-legacy", PRINCIPAL, legacy,
            SOURCE, manifest, "sha256:archive", "sha256:content", "scenario-a", "Scenario A", NOW);
        state.saveUploadCoordination(new UploadCoordinationSnapshot(Map.of(), Map.of(receipt.id(), receipt), Map.of()));
        uploads = new BundleUploadCoordinator(owner, properties(), state, mock(BundleUploadLifecycle.class));
        assertThat(uploads.validationReceipt(receipt.id(), PRINCIPAL)).isEqualTo(receipt);
        assertThatThrownBy(() -> preparePublication(receipt)).hasMessage("WORKFLOW_REVALIDATION_REQUIRED");
        assertThatThrownBy(() -> uploads.prepareValidation(PRINCIPAL, legacy, SOURCE, manifest, NOW))
            .hasMessage("WORKFLOW_REVALIDATION_REQUIRED");
        assertThat(state.loadUploadCoordination().tickets()).isEmpty();
        verify(owner, never()).create(any());
    }

    private void assertStaleValidation(ValidationUploadTicket ticket) {
        assertThatThrownBy(() -> receive(ticket)).isInstanceOf(UploadRejectedException.class)
            .hasRootCauseMessage("WORKFLOW_VERSION_CONFLICT");
        assertThat(workflow().state()).isEqualTo(ScenarioWorkflowState.GENERATED);
        assertThat(workflow().validation()).isNull();
        assertThat(workflow().generatedFileSetDigest()).isEqualTo("sha256:files-B");
        assertThat(state.loadUploadCoordination().receipts()).isEmpty();
        assertThat(state.loadUploadCoordination().tickets().get(ticket.id()).state()).isEqualTo(UploadTicketState.FAILED);
    }

    private ValidationUploadTicket prepareValidation() {
        return uploads.prepareValidation(PRINCIPAL, UploadWorkflowBinding.workflow(workflow()), SOURCE, manifest, NOW);
    }

    private BundleValidationReceipt validate(ValidationUploadTicket ticket) {
        ValidationUploadOutcome outcome = (ValidationUploadOutcome) receive(ticket);
        return uploads.validationReceipt(outcome.validationReceipt().receiptId(), PRINCIPAL);
    }

    private PublicationUploadTicket preparePublication(BundleValidationReceipt receipt) {
        return uploads.preparePublication(PRINCIPAL, receipt.id(), PublicationMode.CREATE, null, SOURCE, manifest,
            receipt.archiveDigest(), receipt.bundleContentDigest(), NOW);
    }

    private UploadOutcome receive(BundleUploadTicket ticket) {
        return uploads.receive(ticket.id(), PRINCIPAL, "application/zip", archive.length,
            new ByteArrayInputStream(archive), NOW);
    }

    private ScenarioWorkflow workflow() {
        return state.findWorkflow("wf-1").orElseThrow();
    }

    private void regenerate() {
        ScenarioWorkflow replacement = workflow();
        long expectedRevision = replacement.revision();
        replacement.readyToGenerate(expectedRevision, new CapabilityFingerprint("sha256:capabilities-B", NOW));
        replacement.generated(replacement.revision(), "sha256:files-B");
        state.saveWorkflow(replacement, expectedRevision, List.of());
    }

    private OwnerValidationResult validated() {
        return new OwnerValidationResult(true, "scenario-a", "Scenario A", "sha256:content-A", Map.of("ok", true));
    }

    private PocketHiveMcpProperties properties() {
        URI ingress = URI.create("http://127.0.0.1:8080");
        return new PocketHiveMcpProperties(ingress, ingress, McpStateMode.MEMORY,
            temporaryDirectory.resolve("state"), temporaryDirectory.resolve("spool"), Duration.ofMinutes(30),
            Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1), Duration.ofMinutes(5),
            100, 10, 100, 10, 1_000_000, 2, 10, 100_000, 200_000, 20, 200_000, 8, 100,
            List.of("http://127.0.0.1:8080"), List.of("127.0.0.1:8080"), ingress,
            URI.create("http://127.0.0.1:8080/mcp"), URI.create("http://127.0.0.1:8080/oauth/introspect"),
            "mcp", "test-only", "pockethive-mcp", "test-only");
    }
}
