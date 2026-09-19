package io.pockethive.mcp.application;

import io.pockethive.mcp.config.PocketHiveMcpProperties;
import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.SourceMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Responsibility: Coordinate bounded bundle upload lifecycle, capacity, recovery, and persistence.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: RESP-MCP-UPLOAD-COORDINATION - docs/architecture/runtime-responsibilities.md#resp-mcp-upload-coordination.
 */

@Service
public final class BundleUploadCoordinator {
    private static final String ZIP_CONTENT_TYPE = "application/zip";

    private final ScenarioBundleOwnerPort owner;
    private final PocketHiveMcpProperties properties;
    private final ZipBundleInspector inspector;
    private final CoordinationStateRepository stateRepository;
    private final BundleUploadLifecycle lifecycle;
    private final UploadCapabilityAuthority uploadCapabilities;
    private final Map<String, BundleUploadTicket> tickets = new ConcurrentHashMap<>();
    private final Map<String, BundleValidationReceipt> receipts = new ConcurrentHashMap<>();
    private final Map<String, PublicationAttempt> attempts = new ConcurrentHashMap<>();
    private final Map<PrincipalKey, AtomicInteger> principalUploads = new ConcurrentHashMap<>();
    private final AtomicInteger concurrentUploads = new AtomicInteger();
    private final AtomicLong reservedSpoolBytes = new AtomicLong();

    @Autowired
    public BundleUploadCoordinator(ScenarioBundleOwnerPort owner, PocketHiveMcpProperties properties,
                                   CoordinationStateRepository stateRepository,
                                   BundleUploadLifecycle lifecycle,
                                   UploadCapabilityAuthority uploadCapabilities) {
        this.owner = owner;
        this.properties = properties;
        this.stateRepository = stateRepository;
        this.lifecycle = lifecycle;
        this.uploadCapabilities = uploadCapabilities;
        this.inspector = new ZipBundleInspector(properties.maxArchiveFiles(),
            properties.maxArchiveExpandedBytes(), properties.maxArchiveNesting(),
            properties.maxArchiveCompressionRatio());
        initialiseSpool();
        restoreAndRecover();
    }

    BundleUploadCoordinator(ScenarioBundleOwnerPort owner, PocketHiveMcpProperties properties,
                            CoordinationStateRepository stateRepository,
                            BundleUploadLifecycle lifecycle) {
        this(owner, properties, stateRepository, lifecycle, new UploadCapabilityAuthority());
    }

    public ValidationUploadTicket prepareValidation(PrincipalKey principal, UploadWorkflowBinding binding,
                                                     SourceMetadata source, BundleFileManifest manifest,
                                                     Instant now) {
        return prepareValidationWithCapability(principal, binding, source, manifest, now).ticket();
    }

    public PreparedUpload<ValidationUploadTicket> prepareValidationWithCapability(
        PrincipalKey principal, UploadWorkflowBinding binding, SourceMetadata source, BundleFileManifest manifest,
        Instant now) {
        return issueValidation(principal, binding, source, manifest, now);
    }

    public ValidationUploadTicket prepareDirectValidation(PrincipalKey principal,
                                                           SourceMetadata source, BundleFileManifest manifest,
                                                           Instant now) {
        return prepareDirectValidationWithCapability(principal, source, manifest, now).ticket();
    }

    public PreparedUpload<ValidationUploadTicket> prepareDirectValidationWithCapability(
        PrincipalKey principal, SourceMetadata source, BundleFileManifest manifest, Instant now) {
        return issueValidation(principal, UploadWorkflowBinding.direct(), source, manifest, now);
    }

    private synchronized PreparedUpload<ValidationUploadTicket> issueValidation(
        PrincipalKey principal, UploadWorkflowBinding binding, SourceMetadata source,
        BundleFileManifest manifest, Instant now) {
        maintain(now);
        requireCurrentBinding(binding);
        IssuedUploadCapability capability = uploadCapabilities.issue();
        ValidationUploadTicket ticket = new ValidationUploadTicket(id("uv"), principal, binding,
            source, manifest, capability.digest(), now.plus(properties.uploadTicketTtl()));
        tickets.put(ticket.id(), ticket);
        persistOrRestore();
        return new PreparedUpload<>(ticket, capability.value());
    }

    public PublicationUploadTicket preparePublication(PrincipalKey principal, String validationReceiptId,
                                                       PublicationMode mode, String scenarioId,
                                                       SourceMetadata source, BundleFileManifest manifest,
                                                       String expectedArchiveDigest,
                                                       String expectedContentDigest, Instant now) {
        return preparePublicationWithCapability(principal, validationReceiptId, mode, scenarioId, source,
            manifest, expectedArchiveDigest, expectedContentDigest, now).ticket();
    }

    public synchronized PreparedUpload<PublicationUploadTicket> preparePublicationWithCapability(
        PrincipalKey principal, String validationReceiptId, PublicationMode mode, String scenarioId,
        SourceMetadata source, BundleFileManifest manifest, String expectedArchiveDigest,
        String expectedContentDigest, Instant now) {
        maintain(now);
        BundleValidationReceipt receipt = receipt(validationReceiptId, principal);
        requireCurrentBinding(receipt.workflowBinding());
        if (!receipt.source().equals(source) || !receipt.manifest().equals(manifest)
            || !receipt.archiveDigest().equals(expectedArchiveDigest)
            || !receipt.bundleContentDigest().equals(expectedContentDigest)) {
            throw new UploadRejectedException("VALIDATION_RECEIPT_BINDING_MISMATCH");
        }
        if (receipt.workflowBinding().mode() == UploadWorkflowMode.WORKFLOW) {
            lifecycle.requirePublicationCurrent(principal, receipt.workflowBinding());
        }
        String attemptId = id("pa");
        String reconciliationScenarioId = mode == PublicationMode.CREATE ? receipt.scenarioId() : scenarioId;
        PublicationAttempt attempt = new PublicationAttempt(attemptId, principal, mode, reconciliationScenarioId,
            expectedContentDigest, now);
        IssuedUploadCapability capability = uploadCapabilities.issue();
        PublicationUploadTicket ticket = new PublicationUploadTicket(id("up"), principal,
            receipt.workflowBinding(), source, manifest, capability.digest(),
            now.plus(properties.uploadTicketTtl()), attemptId,
            receipt.id(), expectedArchiveDigest, expectedContentDigest, mode, scenarioId);
        attempts.put(attemptId, attempt);
        tickets.put(ticket.id(), ticket);
        persistOrRestore();
        return new PreparedUpload<>(ticket, capability.value());
    }

    public UploadOutcome receiveWithCapability(String ticketId, String uploadCapability, String contentType,
                                               long contentLength, InputStream input, Instant now) {
        BundleUploadTicket ticket = authenticateCapability(ticketId, uploadCapability);
        return receive(ticketId, ticket.principal(), contentType, contentLength, input, now);
    }

    public UploadOutcome receive(String ticketId, PrincipalKey principal, String contentType,
                                 long contentLength, InputStream input, Instant now) {
        BundleUploadTicket ticket = begin(ticketId, principal, contentType, contentLength, now);
        Path spool = null;
        RuntimeException primaryFailure = null;
        try {
            try {
                spool = receiveToSpool(input, contentLength);
                ArchiveInspection inspection = inspector.inspect(spool, ticket.manifest());
                if (ticket instanceof ValidationUploadTicket validationTicket) {
                    return validate(validationTicket, inspection, spool, now);
                }
                return publish((PublicationUploadTicket) ticket, inspection, spool);
            } catch (AmbiguousPublicationException exception) {
                throw exception;
            } catch (PublicationStateSyncException exception) {
                throw exception;
            } catch (ArchiveRejectedException exception) {
                fail(ticket);
                throw new UploadRejectedException(exception.getMessage(), exception);
            } catch (UploadRejectedException exception) {
                fail(ticket);
                throw exception;
            } catch (IOException exception) {
                fail(ticket);
                throw new UploadRejectedException("UPLOAD_RECEIVE_FAILED", exception);
            } catch (RuntimeException exception) {
                fail(ticket);
                throw new UploadRejectedException("UPLOAD_PROCESSING_FAILED", exception);
            }
        } catch (RuntimeException exception) {
            primaryFailure = exception;
            throw exception;
        } finally {
            cleanup(spool, primaryFailure, principal, contentLength);
        }
    }

    public BundleValidationReceipt validationReceipt(String receiptId, PrincipalKey principal) {
        return receipt(receiptId, principal);
    }

    public synchronized PublicationAttempt publicationAttempt(String attemptId, PrincipalKey principal) {
        return PublicationAttempt.restore(attempt(attemptId, principal).snapshot());
    }

    private PublicationAttempt attempt(String attemptId, PrincipalKey principal) {
        PublicationAttempt attempt = attempts.get(attemptId);
        if (attempt == null || !attempt.principal().equals(principal)) {
            throw new UploadRejectedException("PUBLICATION_ATTEMPT_NOT_FOUND");
        }
        return attempt;
    }

    public PublicationAttempt reconcile(String attemptId, PrincipalKey principal) {
        PublicationAttempt attempt = publicationAttempt(attemptId, principal);
        if (attempt.state() != PublicationAttemptState.AMBIGUOUS) {
            throw new UploadRejectedException("PUBLICATION_ATTEMPT_NOT_AMBIGUOUS");
        }
        OwnerScenarioProjection projection = owner.get(attempt.scenarioId());
        return completeReconciliation(attemptId, principal, projection);
    }

    private synchronized PublicationAttempt completeReconciliation(String attemptId, PrincipalKey principal,
                                                                   OwnerScenarioProjection projection) {
        PublicationAttempt current = attempt(attemptId, principal);
        if (current.state() == PublicationAttemptState.AMBIGUOUS
            && current.expectedContentDigest().equals(projection.bundleContentDigest())) {
            current.succeeded(projection.ownerResult());
            persistOrRestore();
        }
        return PublicationAttempt.restore(current.snapshot());
    }

    public synchronized void maintain(Instant now) {
        boolean changed = tickets.entrySet().removeIf(entry -> {
            BundleUploadTicket ticket = entry.getValue();
            return ticket.state() != UploadTicketState.RECEIVING
                && !now.isBefore(ticket.expiresAt().plus(properties.attemptRetention()));
        });
        java.util.Set<String> referencedReceiptIds = tickets.values().stream()
            .filter(PublicationUploadTicket.class::isInstance)
            .map(PublicationUploadTicket.class::cast)
            .map(PublicationUploadTicket::validationReceiptId)
            .collect(java.util.stream.Collectors.toSet());
        changed |= receipts.entrySet().removeIf(entry -> !referencedReceiptIds.contains(entry.getKey())
            && !now.isBefore(entry.getValue().createdAt().plus(properties.receiptRetention())));
        java.util.Set<String> referencedAttemptIds = tickets.values().stream()
            .filter(PublicationUploadTicket.class::isInstance)
            .map(PublicationUploadTicket.class::cast)
            .map(PublicationUploadTicket::attemptId)
            .collect(java.util.stream.Collectors.toSet());
        changed |= attempts.entrySet().removeIf(entry -> !referencedAttemptIds.contains(entry.getKey())
            && !now.isBefore(entry.getValue().createdAt().plus(properties.attemptRetention())));
        if (changed) {
            persistOrRestore();
        }
    }

    private synchronized BundleUploadTicket begin(String ticketId, PrincipalKey principal, String contentType,
                                                   long contentLength, Instant now) {
        BundleUploadTicket ticket = tickets.get(ticketId);
        if (ticket == null || !ticket.principal().equals(principal)) {
            throw new UploadRejectedException("UPLOAD_TICKET_NOT_FOUND");
        }
        if (ticket.state() != UploadTicketState.PREPARED) {
            throw new UploadRejectedException("UPLOAD_TICKET_CONSUMED");
        }
        if (!ZIP_CONTENT_TYPE.equalsIgnoreCase(contentType)) {
            throw new UploadRejectedException("UPLOAD_CONTENT_TYPE_INVALID");
        }
        if (contentLength < 0 || contentLength > properties.maxUploadBytes()) {
            throw new UploadRejectedException("UPLOAD_SIZE_EXCEEDED");
        }
        if (!now.isBefore(ticket.expiresAt())) {
            throw new UploadRejectedException("UPLOAD_TICKET_EXPIRED");
        }
        AtomicInteger principalCount = principalUploads.computeIfAbsent(principal, ignored -> new AtomicInteger());
        if (concurrentUploads.get() >= properties.maxConcurrentUploads()
            || principalCount.get() >= properties.maxConcurrentUploadsPerPrincipal()) {
            throw new UploadRejectedException("UPLOAD_CONCURRENCY_EXCEEDED");
        }
        if (reservedSpoolBytes.get() + contentLength > properties.maxUploadSpoolBytes()) {
            throw new UploadRejectedException("UPLOAD_SPOOL_CAPACITY_EXCEEDED");
        }
        ticket.begin();
        if (ticket instanceof PublicationUploadTicket publication) {
            attempts.get(publication.attemptId()).receiving();
        }
        persistOrRestore();
        concurrentUploads.incrementAndGet();
        principalCount.incrementAndGet();
        reservedSpoolBytes.addAndGet(contentLength);
        return ticket;
    }

    private synchronized BundleUploadTicket authenticateCapability(String ticketId, String uploadCapability) {
        BundleUploadTicket ticket = tickets.get(ticketId);
        if (ticket == null || !uploadCapabilities.matches(uploadCapability, ticket.uploadCapabilityDigest())) {
            throw new UploadAuthenticationException("UPLOAD_AUTHENTICATION_REQUIRED");
        }
        return ticket;
    }

    private UploadOutcome validate(ValidationUploadTicket ticket, ArchiveInspection inspection,
                                   Path spool, Instant now) {
        OwnerValidationResult ownerResult = owner.validate(spool);
        if (!ownerResult.valid() || ownerResult.scenarioId() == null || ownerResult.scenarioId().isBlank()
            || ownerResult.scenarioName() == null || ownerResult.scenarioName().isBlank()
            || ownerResult.bundleContentDigest() == null || ownerResult.bundleContentDigest().isBlank()) {
            throw new UploadRejectedException("SCENARIO_BUNDLE_VALIDATION_FAILED");
        }
        return completeValidation(ticket.id(), inspection, ownerResult, now);
    }

    private synchronized UploadOutcome completeValidation(String ticketId, ArchiveInspection inspection,
                                                          OwnerValidationResult ownerResult, Instant now) {
        ValidationUploadTicket ticket = (ValidationUploadTicket) tickets.get(ticketId);
        BundleValidationReceipt receipt = new BundleValidationReceipt(id("vr"), ticket.principal(),
            ticket.workflowBinding(), ticket.source(), ticket.manifest(), inspection.archiveDigest(),
            ownerResult.bundleContentDigest(), ownerResult.scenarioId(), ownerResult.scenarioName(), now);
        receipts.put(receipt.id(), receipt);
        ticket.consume();
        try {
            if (ticket.workflowBinding().mode() == UploadWorkflowMode.WORKFLOW) {
                lifecycle.validated(ticket.principal(), ticket.workflowBinding(),
                    inspection.archiveDigest(), ownerResult.bundleContentDigest(), snapshot());
            } else {
                persistOrRestore();
            }
        } catch (RuntimeException exception) {
            restore(stateRepository.loadUploadCoordination());
            throw exception;
        }
        return new ValidationUploadOutcome(BundleValidationReceiptView.from(receipt));
    }

    private UploadOutcome publish(PublicationUploadTicket ticket, ArchiveInspection inspection, Path spool) {
        beginPublication(ticket.id(), inspection);
        Object result;
        try {
            result = switch (ticket.mode()) {
                case CREATE -> owner.create(spool);
                case REPLACE -> owner.replace(ticket.scenarioId(), spool);
            };
            if (result == null) {
                throw new OwnerCallAmbiguousException("PUBLICATION_OWNER_RESULT_MISSING");
            }
        } catch (OwnerCallRejectedException exception) {
            rejectPublication(ticket.id());
            throw new UploadRejectedException("PUBLICATION_OWNER_REJECTED", exception);
        } catch (RuntimeException exception) {
            markPublicationAmbiguous(ticket.id());
            throw new AmbiguousPublicationException(ticket.attemptId(), exception);
        }
        return completePublication(ticket.id(), result);
    }

    private synchronized void beginPublication(String ticketId, ArchiveInspection inspection) {
        PublicationUploadTicket ticket = (PublicationUploadTicket) tickets.get(ticketId);
        if (!ticket.expectedArchiveDigest().equals(inspection.archiveDigest())) {
            throw new UploadRejectedException("PUBLICATION_ARCHIVE_DIGEST_MISMATCH");
        }
        if (ticket.workflowBinding().mode() == UploadWorkflowMode.WORKFLOW) {
            lifecycle.requirePublicationCurrent(ticket.principal(), ticket.workflowBinding());
        }
        PublicationAttempt attempt = attempts.get(ticket.attemptId());
        attempt.verified();
        persistOrRestore();
        attempt.ownerCallInFlight();
        persistOrRestore();
    }

    private synchronized void rejectPublication(String ticketId) {
        PublicationUploadTicket ticket = (PublicationUploadTicket) tickets.get(ticketId);
        attempts.get(ticket.attemptId()).failed();
        ticket.consume();
        persistPublication(ticket.attemptId());
    }

    private synchronized void markPublicationAmbiguous(String ticketId) {
        PublicationUploadTicket ticket = (PublicationUploadTicket) tickets.get(ticketId);
        attempts.get(ticket.attemptId()).ambiguous();
        ticket.consume();
        persistPublication(ticket.attemptId());
    }

    private synchronized UploadOutcome completePublication(String ticketId, Object result) {
        PublicationUploadTicket ticket = (PublicationUploadTicket) tickets.get(ticketId);
        PublicationAttempt attempt = attempts.get(ticket.attemptId());
        attempt.succeeded(result);
        ticket.consume();
        persistPublication(attempt.id());
        if (ticket.workflowBinding().mode() == UploadWorkflowMode.WORKFLOW) {
            try {
                lifecycle.published(ticket.principal(), ticket.workflowBinding(), attempt);
            } catch (RuntimeException exception) {
                throw new PublicationStateSyncException(attempt.id(), exception);
            }
        }
        return new PublicationUploadOutcome(PublicationAttemptView.from(attempt));
    }

    private void persistPublication(String attemptId) {
        try {
            persistOrRestore();
        } catch (RuntimeException exception) {
            throw new AmbiguousPublicationException(attemptId, exception);
        }
    }

    private Path receiveToSpool(InputStream input, long declaredLength) throws IOException {
        Path spool = Files.createTempFile(properties.uploadSpoolPath(), "upload-", ".quarantine",
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
        try {
            long count = 0;
            byte[] buffer = new byte[8192];
            try (OutputStream output = Files.newOutputStream(spool)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    count += read;
                    if (count > properties.maxUploadBytes() || count > declaredLength) {
                        throw new UploadRejectedException("UPLOAD_SIZE_EXCEEDED");
                    }
                    output.write(buffer, 0, read);
                }
            }
            if (count != declaredLength) {
                throw new UploadRejectedException("UPLOAD_LENGTH_MISMATCH");
            }
            return spool;
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(spool);
            throw exception;
        }
    }

    private synchronized void fail(BundleUploadTicket ticket) {
        BundleUploadTicket current = tickets.get(ticket.id());
        if (current != null && current.state() == UploadTicketState.RECEIVING) {
            if (current instanceof PublicationUploadTicket publication) {
                PublicationAttempt attempt = attempts.get(publication.attemptId());
                if (attempt.state() == PublicationAttemptState.RECEIVING
                    || attempt.state() == PublicationAttemptState.VERIFIED) {
                    attempt.failed();
                }
            }
            current.fail();
            persistOrRestore();
        }
    }

    private synchronized void release(PrincipalKey principal, long contentLength) {
        concurrentUploads.decrementAndGet();
        AtomicInteger count = principalUploads.get(principal);
        if (count != null && count.decrementAndGet() == 0) {
            principalUploads.remove(principal);
        }
        reservedSpoolBytes.addAndGet(-contentLength);
    }

    private void cleanup(Path spool, RuntimeException primaryFailure, PrincipalKey principal,
                         long contentLength) {
        RuntimeException cleanupFailure = null;
        try {
            deleteSpool(spool);
        } catch (RuntimeException exception) {
            cleanupFailure = exception;
        }
        release(principal, contentLength);
        if (cleanupFailure == null) {
            return;
        }
        if (primaryFailure == null) {
            throw cleanupFailure;
        }
        primaryFailure.addSuppressed(cleanupFailure);
    }

    private synchronized BundleValidationReceipt receipt(String receiptId, PrincipalKey principal) {
        BundleValidationReceipt receipt = receipts.get(receiptId);
        if (receipt == null || !receipt.principal().equals(principal)) {
            throw new UploadRejectedException("VALIDATION_RECEIPT_NOT_FOUND");
        }
        return receipt;
    }

    private void initialiseSpool() {
        try {
            Files.createDirectories(properties.uploadSpoolPath());
            secure(properties.uploadSpoolPath());
        } catch (IOException exception) {
            throw new IllegalStateException("UPLOAD_SPOOL_INITIALIZATION_FAILED", exception);
        }
    }

    private synchronized void restoreAndRecover() {
        restore(stateRepository.loadUploadCoordination());
        boolean changed = false;
        for (BundleUploadTicket ticket : tickets.values()) {
            if (ticket.state() != UploadTicketState.RECEIVING) {
                continue;
            }
            if (ticket instanceof PublicationUploadTicket publication) {
                PublicationAttempt attempt = attempts.get(publication.attemptId());
                if (attempt != null && attempt.state() == PublicationAttemptState.OWNER_CALL_IN_FLIGHT) {
                    ticket.consume();
                } else {
                    ticket.fail();
                }
            } else {
                ticket.fail();
            }
            changed = true;
        }
        // Schema migration can retire a ticket while retaining a possibly completed owner attempt.
        for (PublicationAttempt attempt : attempts.values()) {
            if (attempt.state() == PublicationAttemptState.OWNER_CALL_IN_FLIGHT) {
                attempt.ambiguous();
                changed = true;
            } else if (attempt.state() == PublicationAttemptState.RECEIVING
                || attempt.state() == PublicationAttemptState.VERIFIED) {
                attempt.failed();
                changed = true;
            }
        }
        deleteOrphanedSpoolFiles();
        if (changed) {
            persistOrRestore();
        }
    }

    private synchronized void persistOrRestore() {
        try {
            stateRepository.saveUploadCoordination(snapshot());
        } catch (RuntimeException exception) {
            restore(stateRepository.loadUploadCoordination());
            throw exception;
        }
    }

    private UploadCoordinationSnapshot snapshot() {
        Map<String, UploadTicketSnapshot> ticketSnapshots = new java.util.TreeMap<>();
        tickets.forEach((id, ticket) -> ticketSnapshots.put(id, UploadTicketSnapshot.from(ticket)));
        Map<String, PublicationAttemptSnapshot> attemptSnapshots = new java.util.TreeMap<>();
        attempts.forEach((id, attempt) -> attemptSnapshots.put(id, attempt.snapshot()));
        return new UploadCoordinationSnapshot(ticketSnapshots, receipts, attemptSnapshots);
    }

    private void restore(UploadCoordinationSnapshot snapshot) {
        tickets.clear();
        snapshot.tickets().forEach((id, ticket) -> tickets.put(id, ticket.restore()));
        receipts.clear();
        receipts.putAll(snapshot.receipts());
        attempts.clear();
        snapshot.attempts().forEach((id, attempt) -> attempts.put(id, PublicationAttempt.restore(attempt)));
    }

    private void deleteOrphanedSpoolFiles() {
        try (var files = Files.list(properties.uploadSpoolPath())) {
            files.filter(path -> path.getFileName().toString().startsWith("upload-")
                    && path.getFileName().toString().endsWith(".quarantine"))
                .forEach(BundleUploadCoordinator::deleteSpool);
        } catch (IOException exception) {
            throw new IllegalStateException("UPLOAD_SPOOL_RECOVERY_FAILED", exception);
        }
    }

    private static void secure(Path path) throws IOException {
        try {
            EnumSet<PosixFilePermission> permissions = EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            if (Files.isDirectory(path)) {
                permissions.add(PosixFilePermission.OWNER_EXECUTE);
            }
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            // The deployment hardening gate verifies the effective platform ACL.
        }
    }

    private static void deleteSpool(Path spool) {
        if (spool != null) {
            try {
                Files.deleteIfExists(spool);
            } catch (IOException exception) {
                throw new IllegalStateException("UPLOAD_SPOOL_DELETE_FAILED", exception);
            }
        }
    }

    private static void requireCurrentBinding(UploadWorkflowBinding binding) {
        if (binding.mode() == UploadWorkflowMode.LEGACY_WORKFLOW) {
            throw new UploadRejectedException("WORKFLOW_REVALIDATION_REQUIRED");
        }
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
