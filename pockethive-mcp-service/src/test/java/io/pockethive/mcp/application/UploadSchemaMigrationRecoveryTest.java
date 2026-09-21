package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.mcp.adapter.persistence.AtomicCoordinationStateRepository;
import io.pockethive.mcp.config.McpStateMode;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import io.pockethive.mcp.domain.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class UploadSchemaMigrationRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");
    private static final String SHA = "sha256:" + "a".repeat(64);
    private static final PrincipalKey PRINCIPAL = new PrincipalKey(URI.create("https://issuer.example"), "reviewer");
    private static final PrincipalKey OTHER = new PrincipalKey(PRINCIPAL.issuer(), "other-principal");
    private static final Map<String, Object> OWNER_RESULT = Map.of("id", "scenario-a");
    @TempDir Path temporary;

    @ParameterizedTest(name = "{0} publication initially {1}")
    @MethodSource("legacyAttempts")
    void migrationPreservesHistoryAndRecoversOwnerUncertaintyWithoutReplay(
        UploadWorkflowMode mode, PublicationAttemptState initialState) throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Path statePath = temporary.resolve("state");
        AgentSession session = AgentSession.open("session", PRINCIPAL, NOW, Duration.ofHours(1));
        ScenarioWorkflow workflow = validatedWorkflow(session);
        UploadWorkflowBinding binding = mode == UploadWorkflowMode.DIRECT ? UploadWorkflowBinding.direct()
            : new UploadWorkflowBinding(UploadWorkflowMode.WORKFLOW, workflow.id(), workflow.revision() - 1,
                workflow.generatedFileSetDigest(), workflow.capabilityFingerprint());
        var source = new SourceMetadata("https://git.example/repo", "a".repeat(40), "scenarios/a", SourceVerification.CLIENT_ASSERTED);
        var manifest = new BundleFileManifest(List.of(new BundleFileManifestEntry("scenario.yaml", 4, SHA)));
        var receipt = new BundleValidationReceipt("receipt", PRINCIPAL, binding, source, manifest,
            SHA, SHA, "scenario-a", "Scenario A", NOW);
        var attempt = attempt(initialState);
        var ticket = new PublicationUploadTicket("ticket", PRINCIPAL, binding, source, manifest, SHA,
            NOW.plusSeconds(60), attempt.id(), receipt.id(), SHA, SHA, PublicationMode.CREATE, null);
        if (initialState != PublicationAttemptState.PREPARED) {
            ticket.begin();
            if (initialState == PublicationAttemptState.SUCCEEDED || initialState == PublicationAttemptState.AMBIGUOUS) {
                ticket.consume();
            } else if (initialState == PublicationAttemptState.FAILED) {
                ticket.fail();
            }
        }
        try (var repository = repository(mapper, statePath)) {
            repository.createSession(session);
            repository.createWorkflow(session, workflow);
            repository.saveUploadCoordination(new UploadCoordinationSnapshot(Map.of(ticket.id(), UploadTicketSnapshot.from(ticket)),
                Map.of(receipt.id(), receipt), Map.of(attempt.id(), attempt.snapshot())));
        }
        ObjectNode encoded = (ObjectNode) mapper.readTree(statePath.resolve("state.json").toFile());
        encoded.put("schemaVersion", 3);
        for (String collection : List.of("tickets", "receipts")) {
            encoded.path("uploadCoordination").path(collection).elements().forEachRemaining(item ->
                ((ObjectNode) item.path("workflowBinding")).remove(List.of("preparedRevision", "generatedFileSetDigest", "capabilityFingerprint")));
        }
        mapper.writeValue(statePath.resolve("state.json").toFile(), encoded);
        var owner = mock(ScenarioBundleOwnerPort.class);
        var lifecycle = mock(BundleUploadLifecycle.class);
        var properties = properties();
        PublicationAttemptState expected = switch (initialState) {
            case RECEIVING, VERIFIED -> PublicationAttemptState.FAILED;
            case OWNER_CALL_IN_FLIGHT -> PublicationAttemptState.AMBIGUOUS;
            default -> initialState;
        };
        try (var repository = repository(mapper, statePath)) {
            var coordinator = new BundleUploadCoordinator(owner, properties, repository, lifecycle, new UploadCapabilityAuthority());
            assertThatThrownBy(() -> coordinator.publicationAttempt(attempt.id(), OTHER))
                .hasMessage("PUBLICATION_ATTEMPT_NOT_FOUND");
            assertThat(coordinator.publicationAttempt(attempt.id(), PRINCIPAL).state())
                .as("%s %s must recover using durable owner-call state", mode, initialState).isEqualTo(expected);
            assertThat(repository.findWorkflow(workflow.id()).orElseThrow().snapshot()).isEqualTo(workflow.snapshot());
            var migrated = repository.loadUploadCoordination();
            assertThat(migrated.attempts().get(attempt.id()).state()).isEqualTo(expected);
            if (mode == UploadWorkflowMode.WORKFLOW) {
                assertThat(migrated.tickets()).isEmpty();
                assertThat(migrated.receipts().get(receipt.id()).workflowBinding()).isEqualTo(
                    new UploadWorkflowBinding(UploadWorkflowMode.LEGACY_WORKFLOW, workflow.id(), 0, null, null));
            } else {
                assertThat(migrated.tickets()).containsOnlyKeys(ticket.id());
                assertThat(migrated.receipts().get(receipt.id())).isEqualTo(receipt);
            }
            if (initialState == PublicationAttemptState.SUCCEEDED) {
                assertThat(migrated.attempts().get(attempt.id()).ownerResult()).isEqualTo(OWNER_RESULT);
            }
        }
        // A second process-style load must see the recovery transition, not only an in-memory projection.
        try (var repository = repository(mapper, statePath)) {
            var coordinator = new BundleUploadCoordinator(owner, properties, repository, lifecycle, new UploadCapabilityAuthority());
            assertThat(coordinator.publicationAttempt(attempt.id(), PRINCIPAL).state()).isEqualTo(expected);
            assertThatThrownBy(() -> coordinator.reconcile(attempt.id(), OTHER)).hasMessage("PUBLICATION_ATTEMPT_NOT_FOUND");
            if (expected == PublicationAttemptState.AMBIGUOUS) {
                when(owner.get("scenario-a")).thenReturn(
                    new OwnerScenarioProjection("scenario-a", "sha256:" + "b".repeat(64), OWNER_RESULT),
                    new OwnerScenarioProjection("scenario-a", SHA, OWNER_RESULT));
                assertThat(coordinator.reconcile(attempt.id(), PRINCIPAL).state()).isEqualTo(PublicationAttemptState.AMBIGUOUS);
                assertThat(repository.loadUploadCoordination().attempts().get(attempt.id()).state()).isEqualTo(PublicationAttemptState.AMBIGUOUS);
                assertThat(coordinator.reconcile(attempt.id(), PRINCIPAL).state()).isEqualTo(PublicationAttemptState.SUCCEEDED);
                assertThat(repository.loadUploadCoordination().attempts().get(attempt.id()).ownerResult()).isEqualTo(OWNER_RESULT);
                verify(owner, times(2)).get("scenario-a");
            } else {
                assertThatThrownBy(() -> coordinator.reconcile(attempt.id(), PRINCIPAL)).hasMessage("PUBLICATION_ATTEMPT_NOT_AMBIGUOUS");
                verifyNoInteractions(owner);
            }
            assertThat(repository.findWorkflow(workflow.id()).orElseThrow().snapshot()).isEqualTo(workflow.snapshot());
            verify(owner, never()).create(any());
            verify(owner, never()).replace(any(), any());
            verifyNoInteractions(lifecycle);
        }
    }

    private static ScenarioWorkflow validatedWorkflow(AgentSession session) {
        ScenarioWorkflow workflow = ScenarioWorkflow.create("workflow", session.id(), PRINCIPAL);
        session.addWorkflow(0, workflow.id(), 4);
        for (QaRequirementTopic topic : QaRequirementTopic.values()) {
            long revision = workflow.revision();
            var provenance = new AnswerProvenance(PRINCIPAL, "client", "review", "1", workflow.id(), revision,
                topic.name(), SHA, ElicitationAction.ACCEPT, SHA, NOW);
            workflow.answer(revision, topic, RequirementAnswer.notApplicable("Not required", provenance));
        }
        workflow.readyToGenerate(workflow.revision(), new CapabilityFingerprint(SHA, NOW));
        workflow.generated(workflow.revision(), SHA);
        workflow.validated(workflow.revision(), SHA, SHA);
        return workflow;
    }

    private static PublicationAttempt attempt(PublicationAttemptState state) {
        var attempt = new PublicationAttempt("attempt", PRINCIPAL, PublicationMode.CREATE, "scenario-a", SHA, NOW);
        if (state == PublicationAttemptState.PREPARED) return attempt;
        attempt.receiving();
        if (state == PublicationAttemptState.RECEIVING) return attempt;
        if (state == PublicationAttemptState.FAILED) { attempt.failed(); return attempt; }
        attempt.verified();
        if (state == PublicationAttemptState.VERIFIED) return attempt;
        attempt.ownerCallInFlight();
        if (state == PublicationAttemptState.AMBIGUOUS) attempt.ambiguous();
        if (state == PublicationAttemptState.SUCCEEDED) attempt.succeeded(OWNER_RESULT);
        return attempt;
    }

    private PocketHiveMcpProperties properties() {
        var properties = mock(PocketHiveMcpProperties.class);
        when(properties.uploadSpoolPath()).thenReturn(temporary.resolve("spool"));
        when(properties.maxArchiveFiles()).thenReturn(20);
        when(properties.maxArchiveExpandedBytes()).thenReturn(1_000_000L);
        when(properties.maxArchiveNesting()).thenReturn(5);
        when(properties.maxArchiveCompressionRatio()).thenReturn(100);
        return properties;
    }

    private static Stream<Arguments> legacyAttempts() {
        return Stream.of(UploadWorkflowMode.DIRECT, UploadWorkflowMode.WORKFLOW).flatMap(mode ->
            Arrays.stream(PublicationAttemptState.values()).map(state -> Arguments.of(mode, state)));
    }

    private static AtomicCoordinationStateRepository repository(ObjectMapper mapper, Path path) {
        return new AtomicCoordinationStateRepository(mapper, McpStateMode.FILE, path, 1_000_000, 10, 5);
    }
}