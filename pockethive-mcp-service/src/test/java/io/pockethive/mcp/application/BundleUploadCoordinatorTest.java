package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.mcp.adapter.persistence.AtomicCoordinationStateRepository;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.BundleFileManifestEntry;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.SourceMetadata;
import io.pockethive.mcp.domain.SourceVerification;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundleUploadCoordinatorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void validatesThenPublishesOnlyTheIdenticalFullyVerifiedArchive() throws IOException {
        FakeOwner owner = new FakeOwner();
        BundleUploadCoordinator coordinator = coordinator(owner);
        PrincipalKey principal = principal("qa-lead");
        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n", "seed/data.sql", "select 1;\n"));
        BundleFileManifest manifest = manifest(Map.of(
            "scenario.yaml", "id: safe\n", "seed/data.sql", "select 1;\n"));

        ValidationUploadTicket validation = coordinator.prepareValidation(principal, "wf-1",
            source(), manifest, Instant.now());
        assertThat(validation.uploadPath()).isEqualTo("/mcp/uploads/" + validation.id());
        assertThat(owner.calls).isZero();

        BundleValidationReceiptView receiptView = ((ValidationUploadOutcome) coordinator.receive(validation.id(), principal,
            "application/zip", archive.length, new ByteArrayInputStream(archive), Instant.now())).validationReceipt();
        BundleValidationReceipt receipt = coordinator.validationReceipt(receiptView.receiptId(), principal);
        assertThat(receipt.archiveDigest()).startsWith("sha256:");
        assertThat(receipt.bundleContentDigest()).isEqualTo("sha256:owner-content");
        assertThat(owner.validations).isEqualTo(1);
        assertThat(Files.list(properties().uploadSpoolPath())).isEmpty();

        PublicationUploadTicket publication = coordinator.preparePublication(principal, receipt.id(),
            PublicationMode.CREATE, null, source(), manifest, receipt.archiveDigest(),
            receipt.bundleContentDigest(), Instant.now());
        PublicationUploadOutcome published = (PublicationUploadOutcome) coordinator.receive(publication.id(),
            principal, "application/zip", archive.length, new ByteArrayInputStream(archive), Instant.now());

        assertThat(published.publicationAttempt().state()).isEqualTo(PublicationAttemptState.SUCCEEDED);
        assertThat(owner.creates).isEqualTo(1);
        assertThat(owner.replaces).isZero();
        assertThatThrownBy(() -> coordinator.receive(publication.id(), principal, "application/zip",
            archive.length, new ByteArrayInputStream(archive), Instant.now()))
            .isInstanceOf(UploadRejectedException.class).hasMessageContaining("UPLOAD_TICKET_CONSUMED");
    }

    @Test
    void failsClosedBeforeOwnerCallForWrongPrincipalTypeSizeDigestOrExpiredTicket() throws IOException {
        FakeOwner owner = new FakeOwner();
        BundleUploadCoordinator coordinator = coordinator(owner);
        PrincipalKey ownerPrincipal = principal("owner");
        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n"));
        BundleFileManifest manifest = manifest(Map.of("scenario.yaml", "id: safe\n"));
        ValidationUploadTicket ticket = coordinator.prepareValidation(ownerPrincipal, "wf-1", source(), manifest,
            Instant.parse("2026-08-18T10:00:00Z"));

        assertRejected(() -> coordinator.receive(ticket.id(), principal("other"), "application/zip",
            archive.length, new ByteArrayInputStream(archive), Instant.parse("2026-08-18T10:00:01Z")),
            "UPLOAD_TICKET_NOT_FOUND");
        assertRejected(() -> coordinator.receive(ticket.id(), ownerPrincipal, "text/plain",
            archive.length, new ByteArrayInputStream(archive), Instant.parse("2026-08-18T10:00:01Z")),
            "UPLOAD_CONTENT_TYPE_INVALID");
        assertRejected(() -> coordinator.receive(ticket.id(), ownerPrincipal, "application/zip",
            properties().maxUploadBytes() + 1, new ByteArrayInputStream(archive),
            Instant.parse("2026-08-18T10:00:01Z")), "UPLOAD_SIZE_EXCEEDED");
        assertRejected(() -> coordinator.receive(ticket.id(), ownerPrincipal, "application/zip",
            archive.length, new ByteArrayInputStream(archive), Instant.parse("2026-08-18T10:06:00Z")),
            "UPLOAD_TICKET_EXPIRED");
        assertThat(owner.calls).isZero();
    }

    @Test
    void replaceNeverFallsBackAndLostOwnerResponseIsAmbiguousWithoutReplay() throws IOException {
        FakeOwner owner = new FakeOwner();
        BundleUploadCoordinator coordinator = coordinator(owner);
        PrincipalKey principal = principal("qa-lead");
        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n"));
        BundleFileManifest manifest = manifest(Map.of("scenario.yaml", "id: safe\n"));
        BundleValidationReceipt receipt = validate(coordinator, principal, archive, manifest);
        owner.ambiguous = true;
        PublicationUploadTicket publication = coordinator.preparePublication(principal, receipt.id(),
            PublicationMode.REPLACE, "safe", source(), manifest, receipt.archiveDigest(),
            receipt.bundleContentDigest(), Instant.now());

        assertThatThrownBy(() -> coordinator.receive(publication.id(), principal, "application/zip",
            archive.length, new ByteArrayInputStream(archive), Instant.now()))
            .isInstanceOf(AmbiguousPublicationException.class);
        PublicationAttempt attempt = coordinator.publicationAttempt(publication.attemptId(), principal);
        assertThat(attempt.state()).isEqualTo(PublicationAttemptState.AMBIGUOUS);
        assertThat(owner.replaces).isEqualTo(1);
        assertThat(owner.creates).isZero();
        assertThatThrownBy(() -> coordinator.receive(publication.id(), principal, "application/zip",
            archive.length, new ByteArrayInputStream(archive), Instant.now()))
            .isInstanceOf(UploadRejectedException.class).hasMessageContaining("UPLOAD_TICKET_CONSUMED");
        assertThat(owner.replaces).isEqualTo(1);
    }

    @Test
    void ambiguousCreateReconcilesAgainstTheValidatedScenarioIdWithoutReplaying() throws IOException {
        FakeOwner owner = new FakeOwner();
        BundleUploadCoordinator coordinator = coordinator(owner);
        PrincipalKey principal = principal("qa-lead");
        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n"));
        BundleFileManifest manifest = manifest(Map.of("scenario.yaml", "id: safe\n"));
        BundleValidationReceipt receipt = validate(coordinator, principal, archive, manifest);
        owner.ambiguous = true;
        PublicationUploadTicket publication = coordinator.preparePublication(principal, receipt.id(),
            PublicationMode.CREATE, null, source(), manifest, receipt.archiveDigest(),
            receipt.bundleContentDigest(), Instant.now());

        assertThatThrownBy(() -> coordinator.receive(publication.id(), principal, "application/zip",
            archive.length, new ByteArrayInputStream(archive), Instant.now()))
            .isInstanceOf(AmbiguousPublicationException.class);
        owner.ambiguous = false;

        PublicationAttempt reconciled = coordinator.reconcile(publication.attemptId(), principal);

        assertThat(reconciled.state()).isEqualTo(PublicationAttemptState.SUCCEEDED);
        assertThat(reconciled.scenarioId()).isEqualTo("safe");
        assertThat(owner.creates).isEqualTo(1);
        assertThat(owner.replaces).isZero();
    }

    @Test
    void receiptsSurviveRestartAndInFlightOwnerCallsRecoverAsAmbiguous() throws IOException {
        FakeOwner owner = new FakeOwner();
        PocketHiveMcpProperties properties = properties(PocketHiveMcpProperties.StateMode.FILE);
        PrincipalKey principal = principal("qa-lead");
        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n"));
        BundleFileManifest manifest = manifest(Map.of("scenario.yaml", "id: safe\n"));
        BundleValidationReceipt receipt;

        try (AtomicCoordinationStateRepository firstState = state(properties)) {
            BundleUploadCoordinator first = new BundleUploadCoordinator(owner, properties, firstState,
                lifecycle());
            receipt = validate(first, principal, archive, manifest);
        }

        try (AtomicCoordinationStateRepository restartedState = state(properties)) {
            BundleUploadCoordinator restarted = new BundleUploadCoordinator(owner, properties, restartedState,
                lifecycle());
            assertThat(restarted.validationReceipt(receipt.id(), principal)).isEqualTo(receipt);

            PublicationAttempt attempt = new PublicationAttempt("pa-restart", principal,
                PublicationMode.REPLACE, "safe", receipt.bundleContentDigest(), Instant.now());
            attempt.receiving();
            attempt.verified();
            attempt.ownerCallInFlight();
            PublicationUploadTicket ticket = new PublicationUploadTicket("up-restart", principal,
                UploadWorkflowBinding.workflow("wf-1"),
                source(), manifest, Instant.now().plusSeconds(60), attempt.id(), receipt.id(),
                receipt.archiveDigest(), receipt.bundleContentDigest(), PublicationMode.REPLACE, "safe");
            ticket.begin();
            restartedState.saveUploadCoordination(new UploadCoordinationSnapshot(
                Map.of(ticket.id(), UploadTicketSnapshot.from(ticket)), Map.of(receipt.id(), receipt),
                Map.of(attempt.id(), attempt.snapshot())));
            Files.writeString(properties.uploadSpoolPath().resolve("upload-orphan.quarantine"), "partial");
        }

        try (AtomicCoordinationStateRepository recoveredState = state(properties)) {
            BundleUploadCoordinator recovered = new BundleUploadCoordinator(owner, properties, recoveredState,
                lifecycle());
            assertThat(recovered.publicationAttempt("pa-restart", principal).state())
                .isEqualTo(PublicationAttemptState.AMBIGUOUS);
            assertThatThrownBy(() -> recovered.receive("up-restart", principal, "application/zip",
                archive.length, new ByteArrayInputStream(archive), Instant.now()))
                .isInstanceOf(UploadRejectedException.class)
                .hasMessageContaining("UPLOAD_TICKET_CONSUMED");
            assertThat(Files.list(properties.uploadSpoolPath())).isEmpty();
        }
        assertThat(owner.replaces).isZero();
        assertThat(owner.creates).isZero();
    }

    @Test
    void directValidationAndPublicationRequireNoAuthoringWorkflow() throws IOException {
        FakeOwner owner = new FakeOwner();
        BundleUploadLifecycle forbiddenLifecycle = new BundleUploadLifecycle() {
            @Override
            public void validated(PrincipalKey principal, String workflowId, String archiveDigest,
                                  String bundleContentDigest) {
                throw new AssertionError("direct validation must not touch workflow state");
            }

            @Override
            public void published(PrincipalKey principal, String workflowId, PublicationAttempt attempt) {
                throw new AssertionError("direct publication must not touch workflow state");
            }
        };
        BundleUploadCoordinator coordinator = coordinator(owner, forbiddenLifecycle);
        PrincipalKey principal = principal("qa-lead");
        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n", "setup/init.sh", "#!/bin/sh\n"));
        BundleFileManifest manifest = manifest(Map.of(
            "scenario.yaml", "id: safe\n", "setup/init.sh", "#!/bin/sh\n"));

        ValidationUploadTicket validation = coordinator.prepareDirectValidation(
            principal, source(), manifest, Instant.now());
        BundleValidationReceiptView receiptView = ((ValidationUploadOutcome) coordinator.receive(validation.id(),
            principal, "application/zip", archive.length, new ByteArrayInputStream(archive), Instant.now()))
            .validationReceipt();
        BundleValidationReceipt receipt = coordinator.validationReceipt(receiptView.receiptId(), principal);

        assertThat(receipt.workflowBinding().mode()).isEqualTo(UploadWorkflowMode.DIRECT);
        PublicationUploadTicket publication = coordinator.preparePublication(principal, receipt.id(),
            PublicationMode.CREATE, null, source(), manifest, receipt.archiveDigest(),
            receipt.bundleContentDigest(), Instant.now());
        coordinator.receive(publication.id(), principal, "application/zip", archive.length,
            new ByteArrayInputStream(archive), Instant.now());

        assertThat(owner.validations).isEqualTo(1);
        assertThat(owner.creates).isEqualTo(1);
    }

    @Test
    void terminalUploadStateExpiresAtConfiguredRetentionWithoutBreakingLiveReferences() throws IOException {
        FakeOwner owner = new FakeOwner();
        BundleUploadCoordinator coordinator = coordinator(owner);
        PrincipalKey principal = principal("qa-lead");
        Instant now = Instant.parse("2026-08-18T10:00:00Z");
        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n"));
        BundleFileManifest manifest = manifest(Map.of("scenario.yaml", "id: safe\n"));
        ValidationUploadTicket validation = coordinator.prepareDirectValidation(principal, source(), manifest, now);
        BundleValidationReceiptView receiptView = ((ValidationUploadOutcome) coordinator.receive(validation.id(),
            principal, "application/zip", archive.length, new ByteArrayInputStream(archive), now))
            .validationReceipt();
        BundleValidationReceipt receipt = coordinator.validationReceipt(receiptView.receiptId(), principal);
        PublicationUploadTicket publication = coordinator.preparePublication(principal, receipt.id(),
            PublicationMode.CREATE, null, source(), manifest, receipt.archiveDigest(),
            receipt.bundleContentDigest(), now);

        coordinator.receive(publication.id(), principal, "application/zip", archive.length,
            new ByteArrayInputStream(archive), now.plusSeconds(1));
        coordinator.maintain(now.plus(Duration.ofMinutes(30)));
        assertThat(coordinator.validationReceipt(receipt.id(), principal)).isEqualTo(receipt);
        assertThat(coordinator.publicationAttempt(publication.attemptId(), principal).state())
            .isEqualTo(PublicationAttemptState.SUCCEEDED);

        coordinator.maintain(now.plus(Duration.ofHours(2)));

        assertRejected(() -> coordinator.validationReceipt(receipt.id(), principal),
            "VALIDATION_RECEIPT_NOT_FOUND");
        assertRejected(() -> coordinator.publicationAttempt(publication.attemptId(), principal),
            "PUBLICATION_ATTEMPT_NOT_FOUND");
    }

    @Test
    void ownerSuccessRemainsSucceededWhenWorkflowReceiptSynchronizationFails() throws IOException {
        FakeOwner owner = new FakeOwner();
        BundleUploadLifecycle lifecycle = new BundleUploadLifecycle() {
            @Override
            public void validated(PrincipalKey principal, String workflowId, String archiveDigest,
                                  String bundleContentDigest) {
            }

            @Override
            public void published(PrincipalKey principal, String workflowId, PublicationAttempt attempt) {
                throw new IllegalStateException("state unavailable");
            }
        };
        BundleUploadCoordinator coordinator = coordinator(owner, lifecycle);
        PrincipalKey principal = principal("qa-lead");
        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n"));
        BundleFileManifest manifest = manifest(Map.of("scenario.yaml", "id: safe\n"));
        BundleValidationReceipt receipt = validate(coordinator, principal, archive, manifest);
        PublicationUploadTicket publication = coordinator.preparePublication(principal, receipt.id(),
            PublicationMode.CREATE, null, source(), manifest, receipt.archiveDigest(),
            receipt.bundleContentDigest(), Instant.now());

        assertThatThrownBy(() -> coordinator.receive(publication.id(), principal, "application/zip",
            archive.length, new ByteArrayInputStream(archive), Instant.now()))
            .isInstanceOfSatisfying(PublicationStateSyncException.class,
                exception -> assertThat(exception.attemptId()).isEqualTo(publication.attemptId()));
        assertThat(coordinator.publicationAttempt(publication.attemptId(), principal).state())
            .isEqualTo(PublicationAttemptState.SUCCEEDED);
        assertThat(owner.creates).isEqualTo(1);
        assertRejected(() -> coordinator.receive(publication.id(), principal, "application/zip",
            archive.length, new ByteArrayInputStream(archive), Instant.now()), "UPLOAD_TICKET_CONSUMED");
    }

    @Test
    void concurrentUploadQuotaIsAtomicAndReleasedAfterBothRequestsFinish() throws Exception {
        FakeOwner owner = new FakeOwner();
        BundleUploadCoordinator coordinator = coordinator(owner);
        PrincipalKey principal = principal("qa-lead");
        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n"));
        BundleFileManifest manifest = manifest(Map.of("scenario.yaml", "id: safe\n"));
        ValidationUploadTicket first = coordinator.prepareDirectValidation(principal, source(), manifest,
            Instant.now());
        ValidationUploadTicket second = coordinator.prepareDirectValidation(principal, source(), manifest,
            Instant.now());
        ValidationUploadTicket rejected = coordinator.prepareDirectValidation(principal, source(), manifest,
            Instant.now());
        CountDownLatch reading = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstCall = executor.submit(() -> coordinator.receive(first.id(), principal, "application/zip",
                archive.length, new BlockingInputStream(archive, reading, release), Instant.now()));
            var secondCall = executor.submit(() -> coordinator.receive(second.id(), principal, "application/zip",
                archive.length, new BlockingInputStream(archive, reading, release), Instant.now()));
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();

            assertRejected(() -> coordinator.receive(rejected.id(), principal, "application/zip",
                archive.length, new ByteArrayInputStream(archive), Instant.now()),
                "UPLOAD_CONCURRENCY_EXCEEDED");

            release.countDown();
            assertThat(firstCall.get(5, TimeUnit.SECONDS)).isInstanceOf(ValidationUploadOutcome.class);
            assertThat(secondCall.get(5, TimeUnit.SECONDS)).isInstanceOf(ValidationUploadOutcome.class);
        }

        ValidationUploadTicket afterRelease = coordinator.prepareDirectValidation(principal, source(), manifest,
            Instant.now());
        assertThat(coordinator.receive(afterRelease.id(), principal, "application/zip", archive.length,
            new ByteArrayInputStream(archive), Instant.now())).isInstanceOf(ValidationUploadOutcome.class);
    }

    @Test
    void everyReceiveFailureMarksTheTicketPersistsItAndReleasesAllReservations() throws IOException {
        FakeOwner owner = new FakeOwner();
        PocketHiveMcpProperties properties = properties(PocketHiveMcpProperties.StateMode.MEMORY,
            1, 1, 100_000, 100_000, Duration.ofHours(1), Duration.ofHours(1));
        RecordingStateRepository state = new RecordingStateRepository(UploadCoordinationSnapshot.empty());
        BundleUploadCoordinator coordinator = new BundleUploadCoordinator(owner, properties, state, lifecycle());
        PrincipalKey principal = principal("qa-lead");
        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n"));
        BundleFileManifest manifest = manifest(Map.of("scenario.yaml", "id: safe\n"));

        ValidationUploadTicket malformed = coordinator.prepareDirectValidation(principal, source(), manifest,
            Instant.now());
        byte[] malformedBytes = "not a zip".getBytes(StandardCharsets.UTF_8);
        assertRejected(() -> coordinator.receive(malformed.id(), principal, "application/zip",
            malformedBytes.length, new ByteArrayInputStream(malformedBytes), Instant.now()), "ARCHIVE_INVALID");
        assertThat(ticketState(state, malformed.id())).isEqualTo(UploadTicketState.FAILED);

        ValidationUploadTicket lengthMismatch = coordinator.prepareDirectValidation(principal, source(), manifest,
            Instant.now());
        assertRejected(() -> coordinator.receive(lengthMismatch.id(), principal, "application/zip",
            archive.length + 1L, new ByteArrayInputStream(archive), Instant.now()), "UPLOAD_LENGTH_MISMATCH");
        assertThat(ticketState(state, lengthMismatch.id())).isEqualTo(UploadTicketState.FAILED);

        ValidationUploadTicket ioFailure = coordinator.prepareDirectValidation(principal, source(), manifest,
            Instant.now());
        InputStream failingInput = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("read failed");
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                throw new IOException("read failed");
            }
        };
        assertRejected(() -> coordinator.receive(ioFailure.id(), principal, "application/zip", archive.length,
            failingInput, Instant.now()), "UPLOAD_RECEIVE_FAILED");
        assertThat(ticketState(state, ioFailure.id())).isEqualTo(UploadTicketState.FAILED);

        ValidationUploadTicket processingFailure = coordinator.prepareDirectValidation(principal, source(), manifest,
            Instant.now());
        owner.validationFailure = new IllegalStateException("owner unavailable");
        assertRejected(() -> coordinator.receive(processingFailure.id(), principal, "application/zip",
            archive.length, new ByteArrayInputStream(archive), Instant.now()), "UPLOAD_PROCESSING_FAILED");
        owner.validationFailure = null;
        assertThat(ticketState(state, processingFailure.id())).isEqualTo(UploadTicketState.FAILED);

        ValidationUploadTicket success = coordinator.prepareDirectValidation(principal, source(), manifest,
            Instant.now());
        assertThat(coordinator.receive(success.id(), principal, "application/zip", archive.length,
            new ByteArrayInputStream(archive), Instant.now())).isInstanceOf(ValidationUploadOutcome.class);
        assertCapacityReleased(coordinator);
    }

    @Test
    void cleanupFailureIsPrimaryOnlyWithoutAnEarlierFailureAndOtherwiseIsSuppressed() throws IOException {
        PrincipalKey principal = principal("qa-lead");
        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n"));
        BundleFileManifest manifest = manifest(Map.of("scenario.yaml", "id: safe\n"));

        FakeOwner successfulOwner = new FakeOwner();
        successfulOwner.onValidate = BundleUploadCoordinatorTest::replaceSpoolWithNonEmptyDirectory;
        BundleUploadCoordinator successful = coordinator(successfulOwner);
        ValidationUploadTicket successTicket = successful.prepareDirectValidation(principal, source(), manifest,
            Instant.now());
        assertThatThrownBy(() -> successful.receive(successTicket.id(), principal, "application/zip",
            archive.length, new ByteArrayInputStream(archive), Instant.now()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("UPLOAD_SPOOL_DELETE_FAILED");
        deleteBrokenSpoolDirectories(properties().uploadSpoolPath());

        FakeOwner failingOwner = new FakeOwner();
        failingOwner.onValidate = BundleUploadCoordinatorTest::replaceSpoolWithNonEmptyDirectory;
        failingOwner.validationFailure = new IllegalStateException("owner failed");
        BundleUploadCoordinator failing = coordinator(failingOwner);
        ValidationUploadTicket failingTicket = failing.prepareDirectValidation(principal, source(), manifest,
            Instant.now());
        assertThatThrownBy(() -> failing.receive(failingTicket.id(), principal, "application/zip",
            archive.length, new ByteArrayInputStream(archive), Instant.now()))
            .isInstanceOfSatisfying(UploadRejectedException.class, exception -> {
                assertThat(exception).hasMessageContaining("UPLOAD_PROCESSING_FAILED");
                assertThat(exception.getSuppressed()).singleElement()
                    .isInstanceOfSatisfying(IllegalStateException.class,
                        cleanup -> assertThat(cleanup).hasMessage("UPLOAD_SPOOL_DELETE_FAILED"));
            });
        deleteBrokenSpoolDirectories(properties().uploadSpoolPath());
    }

    @Test
    void validatesAllOwnerResultBranchesAndInvokesWorkflowLifecycleOnlyAfterConsumption() throws IOException {
        PrincipalKey principal = principal("qa-lead");
        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n"));
        BundleFileManifest manifest = manifest(Map.of("scenario.yaml", "id: safe\n"));
        for (OwnerValidationResult invalid : List.of(
            new OwnerValidationResult(false, "safe", "sha256:content", Map.of()),
            new OwnerValidationResult(true, "safe", null, Map.of()),
            new OwnerValidationResult(true, "safe", " ", Map.of()))) {
            FakeOwner owner = new FakeOwner();
            owner.validationResult = invalid;
            RecordingStateRepository state = new RecordingStateRepository(UploadCoordinationSnapshot.empty());
            BundleUploadCoordinator coordinator = new BundleUploadCoordinator(owner, properties(), state,
                lifecycle());
            ValidationUploadTicket ticket = coordinator.prepareValidation(principal, "wf-invalid", source(),
                manifest, Instant.now());
            assertRejected(() -> coordinator.receive(ticket.id(), principal, "application/zip", archive.length,
                new ByteArrayInputStream(archive), Instant.now()), "SCENARIO_BUNDLE_VALIDATION_FAILED");
            assertThat(ticketState(state, ticket.id())).isEqualTo(UploadTicketState.FAILED);
        }

        FakeOwner owner = new FakeOwner();
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        RecordingStateRepository state = new RecordingStateRepository(UploadCoordinationSnapshot.empty());
        BundleUploadCoordinator coordinator = new BundleUploadCoordinator(owner, properties(), state, lifecycle);
        ValidationUploadTicket ticket = coordinator.prepareValidation(principal, "wf-live", source(), manifest,
            Instant.now());
        ValidationUploadOutcome outcome = (ValidationUploadOutcome) coordinator.receive(ticket.id(), principal,
            "application/zip", archive.length, new ByteArrayInputStream(archive), Instant.now());

        assertThat(ticketState(state, ticket.id())).isEqualTo(UploadTicketState.CONSUMED);
        assertThat(lifecycle.validatedWorkflowIds).containsExactly("wf-live");
        assertThat(lifecycle.validatedArchiveDigests).containsExactly(outcome.validationReceipt().archiveDigest());
    }

    @Test
    void persistsEveryPublicationBoundaryAndOwnerRejectionWithoutRetry() throws IOException {
        FakeOwner owner = new FakeOwner();
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        RecordingStateRepository state = new RecordingStateRepository(UploadCoordinationSnapshot.empty());
        BundleUploadCoordinator coordinator = new BundleUploadCoordinator(owner, properties(), state, lifecycle);
        PrincipalKey principal = principal("qa-lead");
        Map<String, String> files = Map.of("scenario.yaml", "id: safe\n", "seed.sql", "select 1;\n");
        byte[] archive = zip(files);
        BundleFileManifest manifest = manifest(files);
        BundleValidationReceipt receipt = validate(coordinator, principal, archive, manifest);

        PublicationUploadTicket malformed = coordinator.preparePublication(principal, receipt.id(),
            PublicationMode.CREATE, null, source(), manifest, receipt.archiveDigest(),
            receipt.bundleContentDigest(), Instant.now());
        byte[] malformedBytes = "not a zip".getBytes(StandardCharsets.UTF_8);
        assertRejected(() -> coordinator.receive(malformed.id(), principal, "application/zip",
            malformedBytes.length, new ByteArrayInputStream(malformedBytes), Instant.now()), "ARCHIVE_INVALID");
        assertThat(ticketState(state, malformed.id())).isEqualTo(UploadTicketState.FAILED);
        assertThat(attemptState(state, malformed.attemptId())).isEqualTo(PublicationAttemptState.FAILED);

        PublicationUploadTicket mismatched = coordinator.preparePublication(principal, receipt.id(),
            PublicationMode.CREATE, null, source(), manifest, receipt.archiveDigest(),
            receipt.bundleContentDigest(), Instant.now());
        byte[] differentArchive = zipWithComment(files, "different archive bytes");
        assertRejected(() -> coordinator.receive(mismatched.id(), principal, "application/zip",
            differentArchive.length, new ByteArrayInputStream(differentArchive), Instant.now()),
            "PUBLICATION_ARCHIVE_DIGEST_MISMATCH");
        assertThat(ticketState(state, mismatched.id())).isEqualTo(UploadTicketState.FAILED);
        assertThat(attemptState(state, mismatched.attemptId())).isEqualTo(PublicationAttemptState.FAILED);
        assertThat(owner.creates).isZero();

        owner.rejected = true;
        PublicationUploadTicket rejected = coordinator.preparePublication(principal, receipt.id(),
            PublicationMode.CREATE, null, source(), manifest, receipt.archiveDigest(),
            receipt.bundleContentDigest(), Instant.now());
        assertRejected(() -> coordinator.receive(rejected.id(), principal, "application/zip", archive.length,
            new ByteArrayInputStream(archive), Instant.now()), "PUBLICATION_OWNER_REJECTED");
        assertThat(ticketState(state, rejected.id())).isEqualTo(UploadTicketState.CONSUMED);
        assertThat(attemptState(state, rejected.attemptId())).isEqualTo(PublicationAttemptState.FAILED);
        owner.rejected = false;

        owner.ambiguous = true;
        PublicationUploadTicket ambiguous = coordinator.preparePublication(principal, receipt.id(),
            PublicationMode.CREATE, null, source(), manifest, receipt.archiveDigest(),
            receipt.bundleContentDigest(), Instant.now());
        assertThatThrownBy(() -> coordinator.receive(ambiguous.id(), principal, "application/zip", archive.length,
            new ByteArrayInputStream(archive), Instant.now())).isInstanceOf(AmbiguousPublicationException.class);
        assertThat(ticketState(state, ambiguous.id())).isEqualTo(UploadTicketState.CONSUMED);
        assertThat(attemptState(state, ambiguous.attemptId())).isEqualTo(PublicationAttemptState.AMBIGUOUS);
        owner.ambiguous = false;

        PublicationUploadTicket success = coordinator.preparePublication(principal, receipt.id(),
            PublicationMode.CREATE, null, source(), manifest, receipt.archiveDigest(),
            receipt.bundleContentDigest(), Instant.now());
        coordinator.receive(success.id(), principal, "application/zip", archive.length,
            new ByteArrayInputStream(archive), Instant.now());
        List<PublicationAttemptState> states = state.saved.stream()
            .map(snapshot -> snapshot.attempts().get(success.attemptId()))
            .filter(java.util.Objects::nonNull)
            .map(PublicationAttemptSnapshot::state)
            .distinct()
            .toList();
        assertThat(states).contains(
            PublicationAttemptState.PREPARED,
            PublicationAttemptState.RECEIVING,
            PublicationAttemptState.VERIFIED,
            PublicationAttemptState.OWNER_CALL_IN_FLIGHT,
            PublicationAttemptState.SUCCEEDED);
        assertThat(ticketState(state, success.id())).isEqualTo(UploadTicketState.CONSUMED);
        assertThat(lifecycle.publishedWorkflowIds).contains("wf-1");
    }

    @Test
    void uploadLimitsAreInclusiveAtTheirBoundaryAndIndependentAcrossPrincipals() throws Exception {
        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n"));
        BundleFileManifest manifest = manifest(Map.of("scenario.yaml", "id: safe\n"));
        PrincipalKey firstPrincipal = principal("first");
        PrincipalKey secondPrincipal = principal("second");

        PocketHiveMcpProperties exactProperties = properties(PocketHiveMcpProperties.StateMode.MEMORY,
            1, 1, archive.length, archive.length, Duration.ofHours(1), Duration.ofHours(1));
        BundleUploadCoordinator exact = new BundleUploadCoordinator(new FakeOwner(), exactProperties,
            new RecordingStateRepository(UploadCoordinationSnapshot.empty()), lifecycle());
        ValidationUploadTicket exactTicket = exact.prepareDirectValidation(firstPrincipal, source(), manifest,
            Instant.now());
        assertThat(exact.receive(exactTicket.id(), firstPrincipal, "Application/Zip", archive.length,
            new ByteArrayInputStream(archive), Instant.now())).isInstanceOf(ValidationUploadOutcome.class);
        ValidationUploadTicket sequential = exact.prepareDirectValidation(firstPrincipal, source(), manifest,
            Instant.now());
        assertThat(exact.receive(sequential.id(), firstPrincipal, "application/zip", archive.length,
            new ByteArrayInputStream(archive), Instant.now())).isInstanceOf(ValidationUploadOutcome.class);
        assertCapacityReleased(exact);

        PocketHiveMcpProperties totalProperties = properties(PocketHiveMcpProperties.StateMode.MEMORY,
            2, 1, 100_000, 200_000, Duration.ofHours(1), Duration.ofHours(1));
        BundleUploadCoordinator total = new BundleUploadCoordinator(new FakeOwner(), totalProperties,
            new RecordingStateRepository(UploadCoordinationSnapshot.empty()), lifecycle());
        ValidationUploadTicket first = total.prepareDirectValidation(firstPrincipal, source(), manifest, Instant.now());
        ValidationUploadTicket second = total.prepareDirectValidation(secondPrincipal, source(), manifest, Instant.now());
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> total.receive(first.id(), firstPrincipal, "application/zip",
                archive.length, new BlockingInputStream(archive, reading, release), Instant.now()));
            assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
            assertRejected(() -> total.receive(second.id(), secondPrincipal, "application/zip", archive.length,
                new ByteArrayInputStream(archive), Instant.now()), "UPLOAD_CONCURRENCY_EXCEEDED");
            release.countDown();
            assertThat(running.get(5, TimeUnit.SECONDS)).isInstanceOf(ValidationUploadOutcome.class);
        }

        PocketHiveMcpProperties spoolProperties = properties(PocketHiveMcpProperties.StateMode.MEMORY,
            2, 2, 100_000, archive.length, Duration.ofHours(1), Duration.ofHours(1));
        BundleUploadCoordinator spool = new BundleUploadCoordinator(new FakeOwner(), spoolProperties,
            new RecordingStateRepository(UploadCoordinationSnapshot.empty()), lifecycle());
        ValidationUploadTicket reserved = spool.prepareDirectValidation(firstPrincipal, source(), manifest, Instant.now());
        ValidationUploadTicket excess = spool.prepareDirectValidation(firstPrincipal, source(), manifest, Instant.now());
        CountDownLatch spoolReading = new CountDownLatch(1);
        CountDownLatch spoolRelease = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> spool.receive(reserved.id(), firstPrincipal, "application/zip",
                archive.length, new BlockingInputStream(archive, spoolReading, spoolRelease), Instant.now()));
            assertThat(spoolReading.await(5, TimeUnit.SECONDS)).isTrue();
            assertRejected(() -> spool.receive(excess.id(), firstPrincipal, "application/zip", 1,
                new ByteArrayInputStream(new byte[]{1}), Instant.now()), "UPLOAD_SPOOL_CAPACITY_EXCEEDED");
            spoolRelease.countDown();
            assertThat(running.get(5, TimeUnit.SECONDS)).isInstanceOf(ValidationUploadOutcome.class);
        }
    }

    @Test
    void sizeAndExpiryBoundariesFailWithTheMostSpecificCode() throws IOException {
        BundleUploadCoordinator coordinator = coordinator(new FakeOwner());
        PrincipalKey principal = principal("qa-lead");
        BundleFileManifest emptyManifest = new BundleFileManifest(List.of());
        Instant now = Instant.parse("2026-08-18T10:00:00Z");

        ValidationUploadTicket negative = coordinator.prepareDirectValidation(principal, source(), emptyManifest, now);
        assertRejected(() -> coordinator.receive(negative.id(), principal, "application/zip", -1,
            new ByteArrayInputStream(new byte[0]), now), "UPLOAD_SIZE_EXCEEDED");

        ValidationUploadTicket zero = coordinator.prepareDirectValidation(principal, source(), emptyManifest, now);
        assertRejected(() -> coordinator.receive(zero.id(), principal, "application/zip", 0,
            new ByteArrayInputStream(new byte[0]), now), "ARCHIVE_INVALID");

        ValidationUploadTicket maximum = coordinator.prepareDirectValidation(principal, source(), emptyManifest, now);
        assertRejected(() -> coordinator.receive(maximum.id(), principal, "application/zip",
            properties().maxUploadBytes(), new ByteArrayInputStream(new byte[0]), now), "UPLOAD_LENGTH_MISMATCH");

        ValidationUploadTicket understated = coordinator.prepareDirectValidation(principal, source(), emptyManifest,
            now);
        assertRejected(() -> coordinator.receive(understated.id(), principal, "application/zip", 1,
            new ByteArrayInputStream(new byte[]{1, 2}), now), "UPLOAD_SIZE_EXCEEDED");

        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n"));
        BundleFileManifest manifest = manifest(Map.of("scenario.yaml", "id: safe\n"));
        ValidationUploadTicket beforeExpiry = coordinator.prepareDirectValidation(principal, source(), manifest, now);
        assertThat(coordinator.receive(beforeExpiry.id(), principal, "application/zip", archive.length,
            new ByteArrayInputStream(archive), beforeExpiry.expiresAt().minusNanos(1)))
            .isInstanceOf(ValidationUploadOutcome.class);
        ValidationUploadTicket atExpiry = coordinator.prepareDirectValidation(principal, source(), manifest, now);
        assertRejected(() -> coordinator.receive(atExpiry.id(), principal, "application/zip", archive.length,
            new ByteArrayInputStream(archive), atExpiry.expiresAt()), "UPLOAD_TICKET_EXPIRED");
    }

    @Test
    void publicationBindingAndReconciliationStateAreFailClosed() throws IOException {
        BundleUploadCoordinator coordinator = coordinator(new FakeOwner());
        PrincipalKey principal = principal("qa-lead");
        Map<String, String> files = Map.of("scenario.yaml", "id: safe\n");
        byte[] archive = zip(files);
        BundleFileManifest manifest = manifest(files);
        BundleValidationReceipt receipt = validate(coordinator, principal, archive, manifest);

        SourceMetadata otherSource = new SourceMetadata(source().repository(), "b".repeat(40),
            source().bundlePath(), SourceVerification.CLIENT_ASSERTED);
        assertRejected(() -> coordinator.preparePublication(principal, receipt.id(), PublicationMode.CREATE,
            null, otherSource, manifest, receipt.archiveDigest(), receipt.bundleContentDigest(), Instant.now()),
            "VALIDATION_RECEIPT_BINDING_MISMATCH");

        PublicationUploadTicket prepared = coordinator.preparePublication(principal, receipt.id(),
            PublicationMode.CREATE, null, source(), manifest, receipt.archiveDigest(),
            receipt.bundleContentDigest(), Instant.now());
        assertRejected(() -> coordinator.reconcile(prepared.attemptId(), principal),
            "PUBLICATION_ATTEMPT_NOT_AMBIGUOUS");
    }

    @Test
    void spoolInitializationAndRecoveryIoFailuresAreExplicit() throws IOException {
        Path spoolFile = temporaryDirectory.resolve("spool-file");
        Files.writeString(spoolFile, "not a directory");
        PocketHiveMcpProperties invalidSpool = propertiesWithSpool(spoolFile);
        assertThatThrownBy(() -> new BundleUploadCoordinator(new FakeOwner(), invalidSpool,
            new RecordingStateRepository(UploadCoordinationSnapshot.empty()), lifecycle()))
            .isInstanceOf(IllegalStateException.class).hasMessage("UPLOAD_SPOOL_INITIALIZATION_FAILED");

        Path recoveryPath = temporaryDirectory.resolve("recovery-spool");
        PocketHiveMcpProperties recoveryProperties = propertiesWithSpool(recoveryPath);
        CoordinationStateRepository state = mock(CoordinationStateRepository.class);
        when(state.loadUploadCoordination()).thenAnswer(ignored -> {
            Files.delete(recoveryPath);
            Files.writeString(recoveryPath, "not a directory");
            return UploadCoordinationSnapshot.empty();
        });
        assertThatThrownBy(() -> new BundleUploadCoordinator(
            new FakeOwner(), recoveryProperties, state, lifecycle()))
            .isInstanceOf(IllegalStateException.class).hasMessage("UPLOAD_SPOOL_RECOVERY_FAILED");
    }

    @Test
    void retentionRunsDuringPrepareAndUsesExactConfiguredBoundaries() {
        Instant created = Instant.parse("2026-08-18T10:00:00Z");
        PrincipalKey principal = principal("qa-lead");
        BundleValidationReceipt receipt = new BundleValidationReceipt("receipt-old", principal,
            UploadWorkflowBinding.direct(), source(), new BundleFileManifest(List.of()), "sha256:archive",
            "sha256:content", "safe", created);
        PublicationAttempt attempt = new PublicationAttempt("attempt-old", principal, PublicationMode.REPLACE,
            "safe", "sha256:content", created);
        ValidationUploadTicket oldTicket = new ValidationUploadTicket("ticket-old", principal,
            UploadWorkflowBinding.direct(), source(), new BundleFileManifest(List.of()), created.plusSeconds(60));
        UploadCoordinationSnapshot initial = new UploadCoordinationSnapshot(
            Map.of(oldTicket.id(), UploadTicketSnapshot.from(oldTicket)), Map.of(receipt.id(), receipt),
            Map.of(attempt.id(), attempt.snapshot()));
        RecordingStateRepository state = new RecordingStateRepository(initial);
        PocketHiveMcpProperties properties = properties(PocketHiveMcpProperties.StateMode.MEMORY,
            2, 10, 100_000, 200_000, Duration.ofHours(1), Duration.ofHours(1));
        BundleUploadCoordinator coordinator = new BundleUploadCoordinator(new FakeOwner(), properties, state,
            lifecycle());

        coordinator.maintain(created.plus(Duration.ofHours(1)).minusNanos(1));
        assertThat(coordinator.validationReceipt(receipt.id(), principal)).isEqualTo(receipt);
        assertThat(coordinator.publicationAttempt(attempt.id(), principal).snapshot())
            .isEqualTo(attempt.snapshot());

        coordinator.maintain(created.plus(Duration.ofHours(1)));
        assertRejected(() -> coordinator.validationReceipt(receipt.id(), principal),
            "VALIDATION_RECEIPT_NOT_FOUND");
        assertRejected(() -> coordinator.publicationAttempt(attempt.id(), principal),
            "PUBLICATION_ATTEMPT_NOT_FOUND");
        assertThat(state.current.receipts()).doesNotContainKey(receipt.id());
        assertThat(state.current.attempts()).doesNotContainKey(attempt.id());

        coordinator.prepareDirectValidation(principal, source(), new BundleFileManifest(List.of()),
            created.plus(Duration.ofHours(2)));
        assertThat(state.current.tickets()).doesNotContainKey(oldTicket.id());
    }

    @Test
    void publicationPrepareRunsRetentionAndReconciliationPersistsSuccess() {
        Instant old = Instant.parse("2026-08-18T10:00:00Z");
        Instant now = old.plus(Duration.ofHours(2));
        PrincipalKey principal = principal("qa-lead");
        BundleFileManifest manifest = new BundleFileManifest(List.of());
        BundleValidationReceipt oldReceipt = new BundleValidationReceipt("old-receipt", principal,
            UploadWorkflowBinding.direct(), source(), manifest, "sha256:old-archive", "sha256:old-content",
            "old", old);
        ValidationUploadTicket oldTicket = new ValidationUploadTicket("old-ticket", principal,
            UploadWorkflowBinding.direct(), source(), manifest, old.plusSeconds(60));
        BundleValidationReceipt currentReceipt = new BundleValidationReceipt("current-receipt", principal,
            UploadWorkflowBinding.direct(), source(), manifest, "sha256:archive", "sha256:owner-content",
            "safe", now);
        PublicationAttempt oldAttempt = new PublicationAttempt("old-attempt", principal,
            PublicationMode.REPLACE, "old", "sha256:old-content", old);
        RecordingStateRepository state = new RecordingStateRepository(new UploadCoordinationSnapshot(
            Map.of(oldTicket.id(), UploadTicketSnapshot.from(oldTicket)),
            Map.of(oldReceipt.id(), oldReceipt, currentReceipt.id(), currentReceipt),
            Map.of(oldAttempt.id(), oldAttempt.snapshot())));
        BundleUploadCoordinator coordinator = new BundleUploadCoordinator(new FakeOwner(), properties(), state,
            lifecycle());

        coordinator.preparePublication(principal, currentReceipt.id(), PublicationMode.CREATE, null, source(),
            manifest, currentReceipt.archiveDigest(), currentReceipt.bundleContentDigest(), now);

        assertThat(state.current.tickets()).doesNotContainKey(oldTicket.id());
        assertThat(state.current.receipts()).doesNotContainKeys(oldReceipt.id());
        assertThat(state.current.attempts()).doesNotContainKeys(oldAttempt.id());

        PublicationAttempt ambiguous = new PublicationAttempt("reconcile", principal, PublicationMode.REPLACE,
            "safe", "sha256:owner-content", now);
        ambiguous.receiving();
        ambiguous.verified();
        ambiguous.ownerCallInFlight();
        ambiguous.ambiguous();
        state.current = new UploadCoordinationSnapshot(state.current.tickets(), state.current.receipts(),
            java.util.stream.Stream.concat(state.current.attempts().entrySet().stream(),
                java.util.stream.Stream.of(Map.entry(ambiguous.id(), ambiguous.snapshot())))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
        BundleUploadCoordinator restarted = new BundleUploadCoordinator(new FakeOwner(), properties(), state,
            lifecycle());
        restarted.reconcile(ambiguous.id(), principal);
        assertThat(state.current.attempts().get(ambiguous.id()).state())
            .isEqualTo(PublicationAttemptState.SUCCEEDED);
    }

    @Test
    void maintenancePersistsWhenOnlyReceiptsOrOnlyAttemptsExpire() {
        Instant created = Instant.parse("2026-08-18T10:00:00Z");
        Instant expired = created.plus(Duration.ofHours(1));
        PrincipalKey principal = principal("qa-lead");
        BundleValidationReceipt receipt = new BundleValidationReceipt("receipt-only", principal,
            UploadWorkflowBinding.direct(), source(), new BundleFileManifest(List.of()), "sha256:archive",
            "sha256:content", "safe", created);
        RecordingStateRepository receiptState = new RecordingStateRepository(new UploadCoordinationSnapshot(
            Map.of(), Map.of(receipt.id(), receipt), Map.of()));
        BundleUploadCoordinator receiptCoordinator = new BundleUploadCoordinator(new FakeOwner(), properties(),
            receiptState, lifecycle());

        receiptCoordinator.maintain(expired);

        assertThat(receiptState.current.receipts()).isEmpty();
        assertThat(receiptState.saved).isNotEmpty();

        PublicationAttempt attempt = new PublicationAttempt("attempt-only", principal, PublicationMode.REPLACE,
            "safe", "sha256:content", created);
        RecordingStateRepository attemptState = new RecordingStateRepository(new UploadCoordinationSnapshot(
            Map.of(), Map.of(), Map.of(attempt.id(), attempt.snapshot())));
        BundleUploadCoordinator attemptCoordinator = new BundleUploadCoordinator(new FakeOwner(), properties(),
            attemptState, lifecycle());

        attemptCoordinator.maintain(expired);

        assertThat(attemptState.current.attempts()).isEmpty();
        assertThat(attemptState.saved).isNotEmpty();
    }

    @Test
    void failureAfterVerificationMarksAttemptAndTicketFailedInTheRecoveredSnapshot() throws IOException {
        FakeOwner owner = new FakeOwner();
        RecordingStateRepository state = new RecordingStateRepository(UploadCoordinationSnapshot.empty());
        BundleUploadCoordinator coordinator = new BundleUploadCoordinator(owner, properties(), state, lifecycle());
        PrincipalKey principal = principal("qa-lead");
        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n"));
        BundleFileManifest manifest = manifest(Map.of("scenario.yaml", "id: safe\n"));
        BundleValidationReceipt receipt = validate(coordinator, principal, archive, manifest);
        PublicationUploadTicket publication = coordinator.preparePublication(principal, receipt.id(),
            PublicationMode.CREATE, null, source(), manifest, receipt.archiveDigest(),
            receipt.bundleContentDigest(), Instant.now());
        state.failOnAttemptState = PublicationAttemptState.OWNER_CALL_IN_FLIGHT;

        assertRejected(() -> coordinator.receive(publication.id(), principal, "application/zip", archive.length,
            new ByteArrayInputStream(archive), Instant.now()), "UPLOAD_PROCESSING_FAILED");

        assertThat(ticketState(state, publication.id())).isEqualTo(UploadTicketState.FAILED);
        assertThat(attemptState(state, publication.attemptId())).isEqualTo(PublicationAttemptState.FAILED);
        assertThat(owner.creates).isZero();
    }

    @Test
    void restartRecoversEveryReceivingStateAndPreservesPreparedStateAndUnrelatedFiles() throws IOException {
        PrincipalKey principal = principal("qa-lead");
        Instant now = Instant.parse("2026-08-18T10:00:00Z");
        BundleFileManifest manifest = new BundleFileManifest(List.of());
        BundleValidationReceipt receipt = new BundleValidationReceipt("receipt", principal,
            UploadWorkflowBinding.direct(), source(), manifest, "sha256:archive", "sha256:content", "safe", now);
        Map<String, UploadTicketSnapshot> tickets = new java.util.LinkedHashMap<>();
        Map<String, PublicationAttemptSnapshot> attempts = new java.util.LinkedHashMap<>();

        ValidationUploadTicket validation = new ValidationUploadTicket("validation-receiving", principal,
            UploadWorkflowBinding.direct(), source(), manifest, now.plusSeconds(60));
        validation.begin();
        tickets.put(validation.id(), UploadTicketSnapshot.from(validation));
        ValidationUploadTicket prepared = new ValidationUploadTicket("validation-prepared", principal,
            UploadWorkflowBinding.direct(), source(), manifest, now.plusSeconds(60));
        tickets.put(prepared.id(), UploadTicketSnapshot.from(prepared));

        addRecoverablePublication(tickets, attempts, receipt, principal, manifest, now,
            "receiving", PublicationAttemptState.RECEIVING);
        addRecoverablePublication(tickets, attempts, receipt, principal, manifest, now,
            "verified", PublicationAttemptState.VERIFIED);
        addRecoverablePublication(tickets, attempts, receipt, principal, manifest, now,
            "in-flight", PublicationAttemptState.OWNER_CALL_IN_FLIGHT);
        RecordingStateRepository state = new RecordingStateRepository(
            new UploadCoordinationSnapshot(tickets, Map.of(receipt.id(), receipt), attempts));
        Path unrelated = properties().uploadSpoolPath().resolve("keep-me.txt");
        Files.createDirectories(unrelated.getParent());
        Files.writeString(unrelated, "keep");

        new BundleUploadCoordinator(new FakeOwner(), properties(), state, lifecycle());

        assertThat(ticketState(state, validation.id())).isEqualTo(UploadTicketState.FAILED);
        assertThat(ticketState(state, prepared.id())).isEqualTo(UploadTicketState.PREPARED);
        assertThat(ticketState(state, "publication-receiving")).isEqualTo(UploadTicketState.FAILED);
        assertThat(attemptState(state, "attempt-receiving")).isEqualTo(PublicationAttemptState.FAILED);
        assertThat(ticketState(state, "publication-verified")).isEqualTo(UploadTicketState.FAILED);
        assertThat(attemptState(state, "attempt-verified")).isEqualTo(PublicationAttemptState.FAILED);
        assertThat(ticketState(state, "publication-in-flight")).isEqualTo(UploadTicketState.CONSUMED);
        assertThat(attemptState(state, "attempt-in-flight")).isEqualTo(PublicationAttemptState.AMBIGUOUS);
        assertThat(state.current.receipts()).containsKey(receipt.id());
        assertThat(unrelated).exists();
    }

    @Test
    void persistenceFailureRestoresTheLastAuthoritativeSnapshot() {
        PrincipalKey principal = principal("qa-lead");
        Instant now = Instant.parse("2026-08-18T10:00:00Z");
        ValidationUploadTicket canonical = new ValidationUploadTicket("canonical", principal,
            UploadWorkflowBinding.direct(), source(), new BundleFileManifest(List.of()), now.plusSeconds(60));
        RecordingStateRepository state = new RecordingStateRepository(new UploadCoordinationSnapshot(
            Map.of(canonical.id(), UploadTicketSnapshot.from(canonical)), Map.of(), Map.of()));
        BundleUploadCoordinator coordinator = new BundleUploadCoordinator(new FakeOwner(), properties(), state,
            lifecycle());
        state.failSave = true;

        assertThatThrownBy(() -> coordinator.prepareDirectValidation(principal, source(),
            new BundleFileManifest(List.of()), now)).isInstanceOf(IllegalStateException.class)
            .hasMessage("state write failed");
        String transientId = state.attempted.tickets().keySet().stream()
            .filter(id -> !id.equals(canonical.id())).findFirst().orElseThrow();
        state.failSave = false;

        assertRejected(() -> coordinator.receive(transientId, principal, "application/zip", 0,
            new ByteArrayInputStream(new byte[0]), now), "UPLOAD_TICKET_NOT_FOUND");
        assertRejected(() -> coordinator.receive(canonical.id(), principal, "text/plain", 0,
            new ByteArrayInputStream(new byte[0]), now), "UPLOAD_CONTENT_TYPE_INVALID");
    }

    @Test
    void persistenceRollbackClearsTransientReceiptsAndAttemptsWithoutClearingCanonicalOnes() throws IOException {
        PrincipalKey principal = principal("qa-lead");
        Instant now = Instant.parse("2026-08-18T10:00:00Z");
        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n"));
        BundleFileManifest manifest = manifest(Map.of("scenario.yaml", "id: safe\n"));
        BundleValidationReceipt canonicalReceipt = new BundleValidationReceipt("canonical-receipt", principal,
            UploadWorkflowBinding.direct(), source(), manifest, "sha256:archive", "sha256:owner-content",
            "safe", now);
        PublicationAttempt canonicalAttempt = new PublicationAttempt("canonical-attempt", principal,
            PublicationMode.REPLACE, "safe", canonicalReceipt.bundleContentDigest(), now);
        RecordingStateRepository state = new RecordingStateRepository(new UploadCoordinationSnapshot(
            Map.of(), Map.of(canonicalReceipt.id(), canonicalReceipt),
            Map.of(canonicalAttempt.id(), canonicalAttempt.snapshot())));
        BundleUploadCoordinator coordinator = new BundleUploadCoordinator(new FakeOwner(), properties(), state,
            lifecycle());

        ValidationUploadTicket validation = coordinator.prepareDirectValidation(principal, source(), manifest, now);
        state.failOnReceiptIncrease = true;
        assertRejected(() -> coordinator.receive(validation.id(), principal, "application/zip", archive.length,
            new ByteArrayInputStream(archive), now), "UPLOAD_PROCESSING_FAILED");
        String transientReceipt = state.failedSnapshots.stream().flatMap(snapshot -> snapshot.receipts().keySet().stream())
            .filter(id -> !id.equals(canonicalReceipt.id())).findFirst().orElseThrow();
        assertRejected(() -> coordinator.validationReceipt(transientReceipt, principal),
            "VALIDATION_RECEIPT_NOT_FOUND");
        assertThat(coordinator.validationReceipt(canonicalReceipt.id(), principal)).isEqualTo(canonicalReceipt);

        state.failOnAttemptIncrease = true;
        assertThatThrownBy(() -> coordinator.preparePublication(principal, canonicalReceipt.id(),
            PublicationMode.CREATE, null, source(), manifest, canonicalReceipt.archiveDigest(),
            canonicalReceipt.bundleContentDigest(), now)).isInstanceOf(IllegalStateException.class)
            .hasMessage("state write failed");
        String transientAttempt = state.failedSnapshots.stream().flatMap(snapshot -> snapshot.attempts().keySet().stream())
            .filter(id -> !id.equals(canonicalAttempt.id())).findFirst().orElseThrow();
        assertRejected(() -> coordinator.publicationAttempt(transientAttempt, principal),
            "PUBLICATION_ATTEMPT_NOT_FOUND");
        assertThat(coordinator.publicationAttempt(canonicalAttempt.id(), principal).snapshot())
            .isEqualTo(canonicalAttempt.snapshot());
    }

    @Test
    void spoolAndQuarantineUseOwnerOnlyPermissions() throws IOException {
        FakeOwner owner = new FakeOwner();
        owner.onValidate = path -> {
            try {
                assertThat(Files.getPosixFilePermissions(path)).containsExactlyInAnyOrder(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            } catch (UnsupportedOperationException exception) {
                // The deployment ACL gate covers non-POSIX platforms.
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        };
        BundleUploadCoordinator coordinator = coordinator(owner);
        try {
            assertThat(Files.getPosixFilePermissions(properties().uploadSpoolPath())).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);
        } catch (UnsupportedOperationException ignored) {
            // The deployment ACL gate covers non-POSIX platforms.
        }
        byte[] archive = zip(Map.of("scenario.yaml", "id: safe\n"));
        BundleFileManifest manifest = manifest(Map.of("scenario.yaml", "id: safe\n"));
        ValidationUploadTicket ticket = coordinator.prepareDirectValidation(principal("qa"), source(), manifest,
            Instant.now());
        coordinator.receive(ticket.id(), principal("qa"), "application/zip", archive.length,
            new ByteArrayInputStream(archive), Instant.now());

        Path zipFile = temporaryDirectory.resolve("non-posix.zip");
        try (FileSystem nonPosix = FileSystems.newFileSystem(
            URI.create("jar:" + zipFile.toUri()), Map.of("create", "true"))) {
            PocketHiveMcpProperties nonPosixProperties = propertiesWithSpool(nonPosix.getPath("/spool"));
            assertThat(new BundleUploadCoordinator(new FakeOwner(), nonPosixProperties,
                new RecordingStateRepository(UploadCoordinationSnapshot.empty()), lifecycle())).isNotNull();
        }
    }

    private BundleValidationReceipt validate(BundleUploadCoordinator coordinator, PrincipalKey principal,
                                         byte[] archive, BundleFileManifest manifest) {
        ValidationUploadTicket ticket = coordinator.prepareValidation(principal, "wf-1", source(), manifest,
            Instant.now());
        BundleValidationReceiptView receipt = ((ValidationUploadOutcome) coordinator.receive(ticket.id(), principal,
            "application/zip", archive.length, new ByteArrayInputStream(archive), Instant.now())).validationReceipt();
        return coordinator.validationReceipt(receipt.receiptId(), principal);
    }

    private BundleUploadCoordinator coordinator(FakeOwner owner) {
        return coordinator(owner, lifecycle());
    }

    private BundleUploadCoordinator coordinator(FakeOwner owner, BundleUploadLifecycle lifecycle) {
        PocketHiveMcpProperties properties = properties();
        CoordinationStateRepository state = state(properties);
        return new BundleUploadCoordinator(owner, properties, state, lifecycle);
    }

    private static UploadTicketState ticketState(RecordingStateRepository state, String ticketId) {
        return state.current.tickets().get(ticketId).state();
    }

    @SuppressWarnings("unchecked")
    private static void assertCapacityReleased(BundleUploadCoordinator coordinator) {
        AtomicInteger concurrent = (AtomicInteger) org.springframework.test.util.ReflectionTestUtils
            .getField(coordinator, "concurrentUploads");
        java.util.concurrent.atomic.AtomicLong bytes = (java.util.concurrent.atomic.AtomicLong)
            org.springframework.test.util.ReflectionTestUtils.getField(coordinator, "reservedSpoolBytes");
        Map<PrincipalKey, AtomicInteger> principals = (Map<PrincipalKey, AtomicInteger>)
            org.springframework.test.util.ReflectionTestUtils.getField(coordinator, "principalUploads");
        assertThat(concurrent).isNotNull();
        assertThat(concurrent.get()).isZero();
        assertThat(bytes).isNotNull();
        assertThat(bytes.get()).isZero();
        assertThat(principals).isEmpty();
    }

    private static PublicationAttemptState attemptState(RecordingStateRepository state, String attemptId) {
        return state.current.attempts().get(attemptId).state();
    }

    private static void replaceSpoolWithNonEmptyDirectory(Path spool) {
        try {
            Files.delete(spool);
            Files.createDirectory(spool);
            Files.writeString(spool.resolve("held"), "prevent deletion");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void deleteBrokenSpoolDirectories(Path spoolRoot) {
        try (var paths = Files.list(spoolRoot)) {
            for (Path path : paths.toList()) {
                if (Files.isDirectory(path)) {
                    try (var children = Files.list(path)) {
                        for (Path child : children.toList()) {
                            Files.delete(child);
                        }
                    }
                    Files.delete(path);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void addRecoverablePublication(Map<String, UploadTicketSnapshot> tickets,
                                                  Map<String, PublicationAttemptSnapshot> attempts,
                                                  BundleValidationReceipt receipt, PrincipalKey principal,
                                                  BundleFileManifest manifest, Instant now, String suffix,
                                                  PublicationAttemptState state) {
        PublicationAttempt attempt = new PublicationAttempt("attempt-" + suffix, principal,
            PublicationMode.REPLACE, "safe", receipt.bundleContentDigest(), now);
        attempt.receiving();
        if (state != PublicationAttemptState.RECEIVING) {
            attempt.verified();
        }
        if (state == PublicationAttemptState.OWNER_CALL_IN_FLIGHT) {
            attempt.ownerCallInFlight();
        }
        PublicationUploadTicket ticket = new PublicationUploadTicket("publication-" + suffix, principal,
            UploadWorkflowBinding.direct(), source(), manifest, now.plusSeconds(60), attempt.id(), receipt.id(),
            receipt.archiveDigest(), receipt.bundleContentDigest(), PublicationMode.REPLACE, "safe");
        ticket.begin();
        tickets.put(ticket.id(), UploadTicketSnapshot.from(ticket));
        attempts.put(attempt.id(), attempt.snapshot());
    }

    private static BundleUploadLifecycle lifecycle() {
        return new BundleUploadLifecycle() {
            @Override
            public void validated(PrincipalKey principal, String workflowId, String archiveDigest,
                                  String bundleContentDigest) {
            }

            @Override
            public void published(PrincipalKey principal, String workflowId, PublicationAttempt attempt) {
            }
        };
    }

    private static AtomicCoordinationStateRepository state(PocketHiveMcpProperties properties) {
        return new AtomicCoordinationStateRepository(new ObjectMapper().findAndRegisterModules(),
            properties.stateMode(), properties.statePath(), properties.maxStateBytes(),
            properties.maxOpenSessions(), properties.maxOpenSessionsPerPrincipal());
    }

    private PocketHiveMcpProperties properties() {
        return properties(PocketHiveMcpProperties.StateMode.MEMORY);
    }

    private PocketHiveMcpProperties properties(PocketHiveMcpProperties.StateMode mode) {
        return properties(mode, 2, 10, 100_000, 200_000, Duration.ofHours(1), Duration.ofHours(1));
    }

    private PocketHiveMcpProperties properties(PocketHiveMcpProperties.StateMode mode,
                                                int maxPerPrincipal, int maxConcurrent,
                                                long maxUploadBytes, long maxSpoolBytes,
                                                Duration attemptRetention, Duration receiptRetention) {
        URI ingress = URI.create("http://127.0.0.1:8080");
        return new PocketHiveMcpProperties(
            ingress, ingress, "2025-11-25", mode,
            temporaryDirectory.resolve("state"), temporaryDirectory.resolve("spool"), Duration.ofMinutes(30),
            Duration.ofHours(1), attemptRetention, receiptRetention, Duration.ofMinutes(5),
            100, 10, 10, 1_000_000, maxPerPrincipal, maxConcurrent, maxUploadBytes, maxSpoolBytes,
            20, 200_000, 8, 100, List.of("http://127.0.0.1:8080"),
            List.of("127.0.0.1:8080"), ingress, URI.create("http://127.0.0.1:8080/mcp"),
            URI.create("http://127.0.0.1:8080/oauth/introspect"), "mcp", "secret",
            "pockethive-mcp", "service-secret");
    }

    private PocketHiveMcpProperties propertiesWithSpool(Path spoolPath) {
        PocketHiveMcpProperties source = properties();
        return new PocketHiveMcpProperties(
            source.pocketHiveIngress(), source.ownerApiBase(), source.protocolRevision(), source.stateMode(), source.statePath(), spoolPath,
            source.openSessionTtl(), source.closedSessionRetention(), source.attemptRetention(),
            source.receiptRetention(), source.uploadTicketTtl(), source.maxOpenSessions(),
            source.maxOpenSessionsPerPrincipal(), source.maxWorkflowsPerSession(), source.maxStateBytes(),
            source.maxConcurrentUploadsPerPrincipal(), source.maxConcurrentUploads(), source.maxUploadBytes(),
            source.maxUploadSpoolBytes(), source.maxArchiveFiles(), source.maxArchiveExpandedBytes(),
            source.maxArchiveNesting(), source.maxArchiveCompressionRatio(), source.allowedOrigins(),
            source.allowedHosts(), source.oauthIssuer(), source.oauthResource(), source.oauthIntrospectionUri(),
            source.oauthIntrospectionClientId(), source.oauthIntrospectionClientSecret(),
            source.downstreamServiceName(), source.downstreamServiceSecret());
    }

    private static PrincipalKey principal(String subject) {
        return new PrincipalKey(URI.create("https://issuer.example"), subject);
    }

    private static SourceMetadata source() {
        return new SourceMetadata("https://git.example/tests.git", "a".repeat(40),
            "scenarios/safe", SourceVerification.CLIENT_ASSERTED);
    }

    private static BundleFileManifest manifest(Map<String, String> files) {
        return new BundleFileManifest(files.entrySet().stream()
            .map(entry -> BundleFileManifestEntry.fromBytes(entry.getKey(),
                entry.getValue().getBytes(StandardCharsets.UTF_8)))
            .toList());
    }

    private static byte[] zip(Map<String, String> files) throws IOException {
        return zipWithComment(files, null);
    }

    private static byte[] zipWithComment(Map<String, String> files, String comment) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(bytes)) {
            if (comment != null) {
                output.setComment(comment);
            }
            for (Map.Entry<String, String> file : files.entrySet()) {
                ZipArchiveEntry entry = new ZipArchiveEntry(file.getKey());
                entry.setUnixMode(0100644);
                output.putArchiveEntry(entry);
                output.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void assertRejected(ThrowingCall call, String code) {
        assertThatThrownBy(call::run).isInstanceOf(UploadRejectedException.class).hasMessageContaining(code);
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }

    private static final class FakeOwner implements ScenarioBundleOwnerPort {
        int calls;
        int validations;
        int creates;
        int replaces;
        boolean ambiguous;
        boolean rejected;
        RuntimeException validationFailure;
        OwnerValidationResult validationResult =
            new OwnerValidationResult(true, "safe", "sha256:owner-content", Map.of("ok", true));
        Consumer<Path> onValidate = ignored -> { };

        @Override
        public OwnerValidationResult validate(Path archive) {
            calls++;
            validations++;
            onValidate.accept(archive);
            if (validationFailure != null) {
                throw validationFailure;
            }
            return validationResult;
        }

        @Override
        public Object create(Path archive) {
            calls++;
            creates++;
            if (ambiguous) {
                throw new OwnerCallAmbiguousException("response lost");
            }
            if (rejected) {
                throw new OwnerCallRejectedException("owner rejected", null);
            }
            return Map.of("id", "safe");
        }

        @Override
        public Object replace(String scenarioId, Path archive) {
            calls++;
            replaces++;
            if (ambiguous) {
                throw new OwnerCallAmbiguousException("response lost");
            }
            if (rejected) {
                throw new OwnerCallRejectedException("owner rejected", null);
            }
            return Map.of("id", scenarioId);
        }

        @Override
        public OwnerScenarioProjection get(String scenarioId) {
            return new OwnerScenarioProjection(scenarioId, "sha256:owner-content", Map.of("id", scenarioId));
        }
    }

    private static final class RecordingLifecycle implements BundleUploadLifecycle {
        private final List<String> validatedWorkflowIds = new ArrayList<>();
        private final List<String> validatedArchiveDigests = new ArrayList<>();
        private final List<String> publishedWorkflowIds = new ArrayList<>();

        @Override
        public void validated(PrincipalKey principal, String workflowId, String archiveDigest,
                              String bundleContentDigest) {
            validatedWorkflowIds.add(workflowId);
            validatedArchiveDigests.add(archiveDigest);
        }

        @Override
        public void published(PrincipalKey principal, String workflowId, PublicationAttempt attempt) {
            publishedWorkflowIds.add(workflowId);
        }
    }

    private static final class RecordingStateRepository implements CoordinationStateRepository {
        private UploadCoordinationSnapshot current;
        private UploadCoordinationSnapshot attempted;
        private final List<UploadCoordinationSnapshot> saved = new ArrayList<>();
        private final List<UploadCoordinationSnapshot> failedSnapshots = new ArrayList<>();
        private boolean failSave;
        private boolean failOnReceiptIncrease;
        private boolean failOnAttemptIncrease;
        private PublicationAttemptState failOnAttemptState;

        private RecordingStateRepository(UploadCoordinationSnapshot initial) {
            this.current = initial;
        }

        @Override public Optional<io.pockethive.mcp.domain.AgentSession> findSession(String sessionId) {
            return Optional.empty();
        }

        @Override public Optional<io.pockethive.mcp.domain.ScenarioWorkflow> findWorkflow(String workflowId) {
            return Optional.empty();
        }

        @Override public List<io.pockethive.mcp.domain.ScenarioWorkflow> findWorkflows(List<String> workflowIds) {
            return List.of();
        }

        @Override public List<Map<String, Object>> findGeneratedFiles(String workflowId) {
            return List.of();
        }

        @Override public void createSession(io.pockethive.mcp.domain.AgentSession session) {
        }

        @Override public void saveSession(io.pockethive.mcp.domain.AgentSession session) {
        }

        @Override public void createWorkflow(io.pockethive.mcp.domain.AgentSession session,
                                             io.pockethive.mcp.domain.ScenarioWorkflow workflow) {
        }

        @Override public void saveWorkflow(io.pockethive.mcp.domain.ScenarioWorkflow workflow,
                                           List<Map<String, Object>> generatedFiles) {
        }

        @Override public void saveWorkflow(io.pockethive.mcp.domain.ScenarioWorkflow workflow) {
        }

        @Override public void saveWorkflowAndRemoveGeneratedFiles(
            io.pockethive.mcp.domain.ScenarioWorkflow workflow) {
        }

        @Override public long countOpenSessions(PrincipalKey principal) {
            return 0;
        }

        @Override public UploadCoordinationSnapshot loadUploadCoordination() {
            return current;
        }

        @Override public void saveUploadCoordination(UploadCoordinationSnapshot uploadCoordination) {
            attempted = uploadCoordination;
            boolean stateFailure = failOnAttemptState != null && uploadCoordination.attempts().values().stream()
                .anyMatch(attempt -> attempt.state() == failOnAttemptState);
            if (stateFailure) {
                failOnAttemptState = null;
            }
            boolean receiptFailure = failOnReceiptIncrease
                && uploadCoordination.receipts().size() > current.receipts().size();
            if (receiptFailure) {
                failOnReceiptIncrease = false;
            }
            boolean attemptFailure = failOnAttemptIncrease
                && uploadCoordination.attempts().size() > current.attempts().size();
            if (attemptFailure) {
                failOnAttemptIncrease = false;
            }
            if (failSave || stateFailure || receiptFailure || attemptFailure) {
                failedSnapshots.add(uploadCoordination);
                throw new IllegalStateException("state write failed");
            }
            current = uploadCoordination;
            saved.add(uploadCoordination);
        }

        @Override public void maintainSessions(Instant now, Duration terminalRetention) {
        }
    }

    private static final class BlockingInputStream extends ByteArrayInputStream {
        private final CountDownLatch reading;
        private final CountDownLatch release;
        private boolean blocked;

        private BlockingInputStream(byte[] bytes, CountDownLatch reading, CountDownLatch release) {
            super(bytes);
            this.reading = reading;
            this.release = release;
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            if (!blocked) {
                blocked = true;
                reading.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test upload was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test upload interrupted", exception);
                }
            }
            return super.read(buffer, offset, length);
        }
    }
}
