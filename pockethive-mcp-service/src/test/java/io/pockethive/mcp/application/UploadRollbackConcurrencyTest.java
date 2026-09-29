package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.mcp.adapter.persistence.AtomicCoordinationStateRepository;
import io.pockethive.mcp.config.McpStateMode;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import io.pockethive.mcp.domain.AgentSession;
import io.pockethive.mcp.domain.AnswerProvenance;
import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.BundleFileManifestEntry;
import io.pockethive.mcp.domain.CapabilityFingerprint;
import io.pockethive.mcp.domain.ElicitationAction;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.QaRequirementTopic;
import io.pockethive.mcp.domain.RequirementAnswer;
import io.pockethive.mcp.domain.ScenarioWorkflow;
import io.pockethive.mcp.domain.ScenarioWorkflowState;
import io.pockethive.mcp.domain.SourceMetadata;
import io.pockethive.mcp.domain.SourceVerification;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.stubbing.Answer;

class UploadRollbackConcurrencyTest {
    private static final Instant NOW = Instant.parse("2026-09-18T12:00:00Z");
    private static final PrincipalKey PRINCIPAL = new PrincipalKey(URI.create("https://issuer.example"), "qa-lead");
    private static final SourceMetadata SOURCE = new SourceMetadata("https://git.example/scenarios",
        "a".repeat(40), "scenarios/a", SourceVerification.CLIENT_ASSERTED);
    private static final Map<String, String> OWNER_RESULT = Map.of("id", "scenario-a");

    @TempDir Path temporaryDirectory;
    private ObjectMapper mapper;
    private AtomicCoordinationStateRepository state;
    private ScenarioBundleOwnerPort owner;
    private BundleUploadCoordinator uploads;
    private BundleFileManifest manifest;
    private byte[] archive;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper().findAndRegisterModules();
        state = spy(new AtomicCoordinationStateRepository(mapper, McpStateMode.MEMORY,
            temporaryDirectory.resolve("coordination"), 1_000_000, 10, 5));
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
        uploads = coordinator();
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

    @ParameterizedTest(name = "{0} retains {1} after another validation rolls back")
    @MethodSource("publicationOutcomes")
    void staleValidationCannotLoseConcurrentPublicationOutcome(PublicationMode mode,
                                                              PublicationAttemptState expectedState,
                                                              RuntimeException ownerFailure) throws Exception {
        ValidationUploadTicket stale = prepareValidation();
        publishAcrossRollback(mode, expectedState, ownerFailure, () -> {
            assertThatThrownBy(() -> receive(stale)).isInstanceOf(UploadRejectedException.class)
                .hasRootCauseMessage("WORKFLOW_VERSION_CONFLICT");
            assertThat(state.loadUploadCoordination().tickets().get(stale.id()).state())
                .isEqualTo(UploadTicketState.FAILED);
            assertThat(state.loadUploadCoordination().receipts()).hasSize(1);
        });
    }

    @Test
    void failedPreparationSaveCannotLoseConcurrentSuccessfulPublication() throws Exception {
        publishAcrossRollback(PublicationMode.CREATE, PublicationAttemptState.SUCCEEDED, null, () -> {
            UploadCoordinationSnapshot before = state.loadUploadCoordination();
            doThrow(new IllegalStateException("injected preparation save failure"))
                .doCallRealMethod().when(state).saveUploadCoordination(any());
            assertThatThrownBy(() -> uploads.prepareDirectValidation(PRINCIPAL, SOURCE, manifest, NOW))
                .isInstanceOf(IllegalStateException.class).hasMessage("injected preparation save failure");
            assertThat(state.loadUploadCoordination()).isEqualTo(before);
        });
    }

    @Test
    void staleValidationCannotLeaveConcurrentAcceptedValidationTicketReceiving() throws Exception {
        ValidationUploadTicket stale = prepareValidation();
        regenerate();
        ValidationUploadTicket current = prepareValidation();
        long generatedRevision = workflow().revision();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch allowOwnerToComplete = new CountDownLatch(1);
        AtomicBoolean firstValidation = new AtomicBoolean(true);
        when(owner.validate(any())).thenAnswer(invocation -> {
            if (firstValidation.compareAndSet(true, false)) {
                ownerEntered.countDown();
                assertThat(allowOwnerToComplete.await(20, TimeUnit.SECONDS)).isTrue();
            }
            return validated();
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> receive(current));
            try {
                assertThat(ownerEntered.await(20, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> receive(stale)).isInstanceOf(UploadRejectedException.class)
                    .hasRootCauseMessage("WORKFLOW_VERSION_CONFLICT");
            } finally {
                allowOwnerToComplete.countDown();
            }
            ValidationUploadOutcome returned = (ValidationUploadOutcome) result.get(20, TimeUnit.SECONDS);
            BundleValidationReceipt receipt = uploads.validationReceipt(
                returned.validationReceipt().receiptId(), PRINCIPAL);
            UploadCoordinationSnapshot persisted = state.loadUploadCoordination();
            assertThat(persisted.receipts()).containsExactly(Map.entry(receipt.id(), receipt));
            assertThat(persisted.tickets().get(stale.id()).state()).isEqualTo(UploadTicketState.FAILED);
            assertThat(persisted.tickets().get(current.id()).state()).isEqualTo(UploadTicketState.CONSUMED);
            assertThat(workflow().state()).isEqualTo(ScenarioWorkflowState.VALIDATED);
            assertThat(workflow().revision()).isEqualTo(generatedRevision + 1);
            assertThat(workflow().generatedFileSetDigest()).isEqualTo("sha256:files-B");
            assertThat(workflow().validation().archiveDigest()).isEqualTo(receipt.archiveDigest());
            assertThatThrownBy(() -> receive(current)).hasMessage("UPLOAD_TICKET_CONSUMED");
            assertThat(coordinator().validationReceipt(receipt.id(), PRINCIPAL)).isEqualTo(receipt);
            assertThat(state.loadUploadCoordination()).isEqualTo(persisted);
            verify(owner, never()).create(any());
            verify(owner, never()).replace(any(), any());
        }
    }

    @ParameterizedTest(name = "unpersisted {0} remains recoverable without owner replay")
    @EnumSource(value = PublicationAttemptState.class, names = {"SUCCEEDED", "FAILED", "AMBIGUOUS"})
    void terminalSaveFailureRetainsRecoverableOwnerCall(PublicationAttemptState ownerOutcome) {
        BundleValidationReceipt receipt = validate(prepareValidation());
        PublicationUploadTicket publication = preparePublication(receipt);
        var validatedWorkflow = workflow().snapshot();
        when(owner.create(any())).thenAnswer(invocation -> {
            doThrow(new IllegalStateException("injected terminal save failure"))
                .doCallRealMethod().when(state).saveUploadCoordination(any());
            return switch (ownerOutcome) {
                case SUCCEEDED -> OWNER_RESULT;
                case FAILED -> throw new OwnerCallRejectedException("owner rejected", null);
                case AMBIGUOUS -> throw new OwnerCallAmbiguousException("owner response lost");
                default -> throw new AssertionError("Unexpected fixture outcome: " + ownerOutcome);
            };
        });
        assertThatThrownBy(() -> receive(publication)).isInstanceOf(AmbiguousPublicationException.class)
            .hasRootCauseMessage("injected terminal save failure")
            .satisfies(exception -> assertThat(((AmbiguousPublicationException) exception).attemptId())
                .isEqualTo(publication.attemptId()));
        UploadCoordinationSnapshot unresolved = state.loadUploadCoordination();
        assertThat(unresolved.tickets().get(publication.id()).state()).isEqualTo(UploadTicketState.RECEIVING);
        assertThat(unresolved.attempts().get(publication.attemptId()).state())
            .isEqualTo(PublicationAttemptState.OWNER_CALL_IN_FLIGHT);
        assertThat(uploads.publicationAttempt(publication.attemptId(), PRINCIPAL).snapshot())
            .isEqualTo(unresolved.attempts().get(publication.attemptId()));
        assertThat(workflow().snapshot()).isEqualTo(validatedWorkflow);
        assertThatThrownBy(() -> receive(publication)).hasMessage("UPLOAD_TICKET_CONSUMED");

        uploads = coordinator();
        UploadCoordinationSnapshot recovered = state.loadUploadCoordination();
        assertThat(recovered.tickets().get(publication.id()).state()).isEqualTo(UploadTicketState.CONSUMED);
        assertThat(recovered.attempts().get(publication.attemptId()).state())
            .isEqualTo(PublicationAttemptState.AMBIGUOUS);
        assertThat(uploads.publicationAttempt(publication.attemptId(), PRINCIPAL).snapshot())
            .isEqualTo(recovered.attempts().get(publication.attemptId()));
        assertThat(workflow().snapshot()).isEqualTo(validatedWorkflow);
        assertThatThrownBy(() -> receive(publication)).hasMessage("UPLOAD_TICKET_CONSUMED");
        verify(owner).create(any());
        verify(owner, never()).replace(any(), any());
    }

    @Test
    void reconciliationRetainsCanonicalSuccessAfterStaleValidationRollsBack() throws Exception {
        ValidationUploadTicket stale = prepareValidation();
        PublicationUploadTicket publication = ambiguousPublication();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch allowOwnerToComplete = new CountDownLatch(1);
        when(owner.get("scenario-a")).thenAnswer(invocation -> {
            ownerEntered.countDown();
            assertThat(allowOwnerToComplete.await(20, TimeUnit.SECONDS)).isTrue();
            return new OwnerScenarioProjection("scenario-a", "sha256:content-A", OWNER_RESULT);
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> uploads.reconcile(publication.attemptId(), PRINCIPAL));
            try {
                assertThat(ownerEntered.await(20, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> receive(stale)).isInstanceOf(UploadRejectedException.class)
                    .hasRootCauseMessage("WORKFLOW_VERSION_CONFLICT");
            } finally {
                allowOwnerToComplete.countDown();
            }
            PublicationAttempt returned = result.get(20, TimeUnit.SECONDS);
            assertThat(returned.state()).isEqualTo(PublicationAttemptState.SUCCEEDED);
            assertThat(returned.ownerResult()).isEqualTo(OWNER_RESULT);
            assertThat(uploads.publicationAttempt(publication.attemptId(), PRINCIPAL).snapshot())
                .isEqualTo(returned.snapshot());
            assertThat(state.loadUploadCoordination().attempts().get(publication.attemptId()))
                .isEqualTo(returned.snapshot());
            assertThat(state.loadUploadCoordination().tickets().get(publication.id()).state())
                .isEqualTo(UploadTicketState.CONSUMED);
            assertThatThrownBy(() -> receive(publication)).hasMessage("UPLOAD_TICKET_CONSUMED");
            verify(owner).create(any());
            verify(owner).get("scenario-a");
        }
    }

    @Test
    void concurrentReconciliationsReturnTheSameCanonicalSuccess() throws Exception {
        PublicationUploadTicket publication = ambiguousPublication();
        CountDownLatch ownersEntered = new CountDownLatch(2);
        CountDownLatch allowOwnersToComplete = new CountDownLatch(1);
        when(owner.get("scenario-a")).thenAnswer(invocation -> {
            ownersEntered.countDown();
            assertThat(allowOwnersToComplete.await(20, TimeUnit.SECONDS)).isTrue();
            return new OwnerScenarioProjection("scenario-a", "sha256:content-A", OWNER_RESULT);
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> uploads.reconcile(publication.attemptId(), PRINCIPAL));
            var second = executor.submit(() -> uploads.reconcile(publication.attemptId(), PRINCIPAL));
            try {
                assertThat(ownersEntered.await(20, TimeUnit.SECONDS)).isTrue();
            } finally {
                allowOwnersToComplete.countDown();
            }
            PublicationAttempt firstResult = first.get(20, TimeUnit.SECONDS);
            PublicationAttempt secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(firstResult.state()).isEqualTo(PublicationAttemptState.SUCCEEDED);
            assertThat(secondResult.snapshot()).isEqualTo(firstResult.snapshot());
            assertThat(firstResult.ownerResult()).isEqualTo(OWNER_RESULT);
            assertThat(uploads.publicationAttempt(publication.attemptId(), PRINCIPAL).snapshot())
                .isEqualTo(firstResult.snapshot());
            assertThat(state.loadUploadCoordination().attempts().get(publication.attemptId()))
                .isEqualTo(firstResult.snapshot());
            assertThat(state.loadUploadCoordination().tickets().get(publication.id()).state())
                .isEqualTo(UploadTicketState.CONSUMED);
            verify(owner).create(any());
            verify(owner, times(2)).get("scenario-a");
        }
    }

    @Test
    void reconciliationCannotResurrectAnAttemptRemovedDuringOwnerLookup() throws Exception {
        PublicationUploadTicket publication = ambiguousPublication();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch allowOwnerToComplete = new CountDownLatch(1);
        when(owner.get("scenario-a")).thenAnswer(invocation -> {
            ownerEntered.countDown();
            assertThat(allowOwnerToComplete.await(20, TimeUnit.SECONDS)).isTrue();
            return new OwnerScenarioProjection("scenario-a", "sha256:content-A", OWNER_RESULT);
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> uploads.reconcile(publication.attemptId(), PRINCIPAL));
            try {
                assertThat(ownerEntered.await(20, TimeUnit.SECONDS)).isTrue();
                uploads.maintain(NOW.plus(Duration.ofDays(2)));
                assertThat(state.loadUploadCoordination().attempts()).isEmpty();
            } finally {
                allowOwnerToComplete.countDown();
            }
            assertThatThrownBy(() -> result.get(20, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(UploadRejectedException.class)
                .hasRootCauseMessage("PUBLICATION_ATTEMPT_NOT_FOUND");
            assertThatThrownBy(() -> uploads.publicationAttempt(publication.attemptId(), PRINCIPAL))
                .hasMessage("PUBLICATION_ATTEMPT_NOT_FOUND");
            assertThat(state.loadUploadCoordination().attempts()).isEmpty();
            assertThat(state.loadUploadCoordination().tickets()).isEmpty();
            verify(owner).create(any());
            verify(owner).get("scenario-a");
        }
    }

    @ParameterizedTest(name = "{0} without an owner result remains ambiguous")
    @EnumSource(PublicationMode.class)
    void missingOwnerResultDoesNotReportSuccessOrPermitReplay(PublicationMode mode) {
        BundleValidationReceipt receipt = validate(prepareValidation());
        PublicationUploadTicket publication = uploads.preparePublication(PRINCIPAL, receipt.id(), mode,
            mode == PublicationMode.REPLACE ? "scenario-a" : null, SOURCE, manifest,
            receipt.archiveDigest(), receipt.bundleContentDigest(), NOW);
        var validatedWorkflow = workflow().snapshot();
        when(owner.create(any())).thenReturn(null);
        when(owner.replace(eq("scenario-a"), any())).thenReturn(null);
        assertThatThrownBy(() -> receive(publication)).isInstanceOf(AmbiguousPublicationException.class)
            .hasRootCauseInstanceOf(OwnerCallAmbiguousException.class)
            .hasRootCauseMessage("PUBLICATION_OWNER_RESULT_MISSING")
            .satisfies(exception -> assertThat(((AmbiguousPublicationException) exception).attemptId())
                .isEqualTo(publication.attemptId()));
        PublicationAttempt canonical = uploads.publicationAttempt(publication.attemptId(), PRINCIPAL);
        assertThat(canonical.state()).isEqualTo(PublicationAttemptState.AMBIGUOUS);
        assertThat(canonical.ownerResult()).isNull();
        assertThat(state.loadUploadCoordination().attempts().get(publication.attemptId()))
            .isEqualTo(canonical.snapshot());
        assertThat(state.loadUploadCoordination().tickets().get(publication.id()).state())
            .isEqualTo(UploadTicketState.CONSUMED);
        assertThat(workflow().snapshot()).isEqualTo(validatedWorkflow);
        assertThatThrownBy(() -> receive(publication)).hasMessage("UPLOAD_TICKET_CONSUMED");
        if (mode == PublicationMode.CREATE) {
            verify(owner).create(any());
            verify(owner, never()).replace(any(), any());
        } else {
            verify(owner).replace(eq("scenario-a"), any());
            verify(owner, never()).create(any());
        }
    }

    @Test
    void retentionDuringRejectionSaveDoesNotReplaceOwnerFailureOrResurrectTicket() {
        PublicationUploadTicket publication = preparePublication(validate(prepareValidation()));
        var validatedWorkflow = workflow().snapshot();
        when(owner.create(any())).thenThrow(new OwnerCallRejectedException("owner rejected", null));
        AtomicBoolean cleanupPending = new AtomicBoolean(true);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            UploadCoordinationSnapshot saved = invocation.getArgument(0);
            PublicationAttemptSnapshot attempt = saved.attempts().get(publication.attemptId());
            if (attempt != null && attempt.state() == PublicationAttemptState.FAILED
                && cleanupPending.compareAndSet(true, false)) {
                uploads.maintain(NOW.plus(Duration.ofHours(3)));
            }
            return null;
        }).when(state).saveUploadCoordination(any());
        assertThatThrownBy(() -> receive(publication)).isInstanceOf(UploadRejectedException.class)
            .hasMessage("PUBLICATION_OWNER_REJECTED").hasRootCauseMessage("owner rejected");
        assertThat(cleanupPending).isFalse();
        assertThat(state.loadUploadCoordination().tickets()).isEmpty();
        assertThat(state.loadUploadCoordination().attempts()).isEmpty();
        assertThatThrownBy(() -> uploads.publicationAttempt(publication.attemptId(), PRINCIPAL))
            .hasMessage("PUBLICATION_ATTEMPT_NOT_FOUND");
        assertThatThrownBy(() -> receive(publication)).hasMessage("UPLOAD_TICKET_NOT_FOUND");
        assertThat(workflow().snapshot()).isEqualTo(validatedWorkflow);
        verify(owner).create(any());
        verify(owner, never()).replace(any(), any());
    }

    private PublicationUploadTicket ambiguousPublication() {
        PublicationUploadTicket publication = preparePublication(validate(prepareValidation()));
        when(owner.create(any())).thenThrow(new OwnerCallAmbiguousException("owner response lost"));
        assertThatThrownBy(() -> receive(publication)).isInstanceOf(AmbiguousPublicationException.class);
        return publication;
    }

    private PublicationUploadTicket preparePublication(BundleValidationReceipt receipt) {
        return uploads.preparePublication(PRINCIPAL, receipt.id(), PublicationMode.CREATE, null, SOURCE, manifest,
            receipt.archiveDigest(), receipt.bundleContentDigest(), NOW);
    }

    private void publishAcrossRollback(PublicationMode mode, PublicationAttemptState expectedState,
                                       RuntimeException ownerFailure, Runnable rollback) throws Exception {
        BundleValidationReceipt receipt = validate(prepareValidation());
        PublicationUploadTicket publication = uploads.preparePublication(PRINCIPAL, receipt.id(), mode,
            mode == PublicationMode.REPLACE ? "scenario-a" : null, SOURCE, manifest,
            receipt.archiveDigest(), receipt.bundleContentDigest(), NOW);
        var validatedWorkflow = workflow().snapshot();
        CountDownLatch ownerEntered = new CountDownLatch(1);
        CountDownLatch allowOwnerToComplete = new CountDownLatch(1);
        Answer<Object> ownerCall = invocation -> {
            ownerEntered.countDown();
            assertThat(allowOwnerToComplete.await(20, TimeUnit.SECONDS)).isTrue();
            if (ownerFailure != null) {
                throw ownerFailure;
            }
            return OWNER_RESULT;
        };
        when(owner.create(any())).thenAnswer(ownerCall);
        when(owner.replace(eq("scenario-a"), any())).thenAnswer(ownerCall);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> receive(publication));
            try {
                assertThat(ownerEntered.await(20, TimeUnit.SECONDS)).isTrue();
                rollback.run();
                assertThat(uploads.publicationAttempt(publication.attemptId(), PRINCIPAL).state())
                    .isEqualTo(PublicationAttemptState.OWNER_CALL_IN_FLIGHT);
                assertThat(state.loadUploadCoordination().tickets().get(publication.id()).state())
                    .isEqualTo(UploadTicketState.RECEIVING);
            } finally {
                allowOwnerToComplete.countDown();
            }
            if (expectedState == PublicationAttemptState.SUCCEEDED) {
                PublicationUploadOutcome returned = (PublicationUploadOutcome) result.get(20, TimeUnit.SECONDS);
                assertThat(returned.publicationAttempt().state()).isEqualTo(expectedState);
                assertThat(workflow().state()).isEqualTo(ScenarioWorkflowState.PUBLISHED);
                assertThat(workflow().revision()).isEqualTo(validatedWorkflow.revision() + 1);
                assertThat(workflow().publicationReceiptDigest()).isNotBlank();
            } else {
                assertThatThrownBy(() -> result.get(20, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(expectedState == PublicationAttemptState.FAILED
                        ? UploadRejectedException.class : AmbiguousPublicationException.class)
                    .hasRootCause(ownerFailure);
                assertThat(workflow().snapshot()).isEqualTo(validatedWorkflow);
            }
            UploadCoordinationSnapshot persisted = state.loadUploadCoordination();
            PublicationAttempt canonical = uploads.publicationAttempt(publication.attemptId(), PRINCIPAL);
            assertThat(canonical.state()).isEqualTo(expectedState);
            assertThat(persisted.attempts().get(publication.attemptId())).isEqualTo(canonical.snapshot());
            assertThat(canonical.ownerResult()).isEqualTo(
                expectedState == PublicationAttemptState.SUCCEEDED ? OWNER_RESULT : null);
            assertThat(persisted.tickets().get(publication.id()).state()).isEqualTo(UploadTicketState.CONSUMED);
            assertThatThrownBy(() -> receive(publication)).hasMessage("UPLOAD_TICKET_CONSUMED");
            assertThat(coordinator().publicationAttempt(publication.attemptId(), PRINCIPAL).snapshot())
                .isEqualTo(canonical.snapshot());
            assertThat(state.loadUploadCoordination()).isEqualTo(persisted);
            if (mode == PublicationMode.CREATE) {
                verify(owner).create(any());
                verify(owner, never()).replace(any(), any());
            } else {
                verify(owner).replace(eq("scenario-a"), any());
                verify(owner, never()).create(any());
            }
        }
    }

    private static Stream<Arguments> publicationOutcomes() {
        return Arrays.stream(PublicationMode.values()).flatMap(mode -> Stream.of(
            Arguments.of(mode, PublicationAttemptState.SUCCEEDED, null),
            Arguments.of(mode, PublicationAttemptState.FAILED, new OwnerCallRejectedException("owner rejected", null)),
            Arguments.of(mode, PublicationAttemptState.AMBIGUOUS, new OwnerCallAmbiguousException("owner response lost"))));
    }

    private ValidationUploadTicket prepareValidation() {
        return uploads.prepareValidation(PRINCIPAL, UploadWorkflowBinding.workflow(workflow()), SOURCE, manifest, NOW);
    }

    private BundleValidationReceipt validate(ValidationUploadTicket ticket) {
        ValidationUploadOutcome outcome = (ValidationUploadOutcome) receive(ticket);
        return uploads.validationReceipt(outcome.validationReceipt().receiptId(), PRINCIPAL);
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

    private BundleUploadCoordinator coordinator() {
        PocketHiveMcpProperties properties = properties();
        return new BundleUploadCoordinator(owner, properties, state,
            new CoordinationWorkflowUploadLifecycle(state, mapper,
                new WorkflowAccess(state, properties, Clock.fixed(NOW, ZoneOffset.UTC))));
    }

    private PocketHiveMcpProperties properties() {
        URI ingress = URI.create("http://127.0.0.1:8080");
        return new PocketHiveMcpProperties(false, ingress, ingress, McpStateMode.MEMORY,
            temporaryDirectory.resolve("state"), temporaryDirectory.resolve("spool"), Duration.ofMinutes(30),
            Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1), Duration.ofMinutes(5),
            100, 10, 100, 10, 1_000_000, 2, 10, 100_000, 200_000, 20, 200_000, 8, 100,
            List.of("http://127.0.0.1:8080"), List.of("127.0.0.1:8080"), ingress,
            URI.create("http://127.0.0.1:8080/mcp"), URI.create("http://127.0.0.1:8080/oauth/introspect"),
            "mcp", "test-only", "pockethive-mcp", "test-only");
    }
}
