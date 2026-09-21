package io.pockethive.mcp.adapter.persistence;
import io.pockethive.mcp.config.McpStateMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.pockethive.mcp.application.BundleValidationReceipt;
import io.pockethive.mcp.application.ToolExecutionException;
import io.pockethive.mcp.application.UploadCoordinationSnapshot;
import io.pockethive.mcp.application.UploadWorkflowBinding;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import io.pockethive.mcp.domain.AgentSession;
import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.BundleFileManifestEntry;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.ScenarioWorkflow;
import io.pockethive.mcp.domain.SourceMetadata;
import io.pockethive.mcp.domain.SourceVerification;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicCoordinationStateRepositoryTest {
    private static final PrincipalKey PRINCIPAL = new PrincipalKey(URI.create("https://issuer.example"), "qa-lead");
    private static final String SHA = "sha256:" + "a".repeat(64);

    @TempDir Path temporaryDirectory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void schemaLeavesNonDocumentNodesUnmigratedForTheRepositoryToReject() {
        var encoded = mapper.createArrayNode();

        CoordinationStateSchema.Migration migration = new CoordinationStateSchema().migrate(encoded);

        assertThat(migration).isNotNull();
        assertThat(migration.encodedState()).isSameAs(encoded);
        assertThat(migration.migrated()).isFalse();
    }

    @Test
    void atomicallyRestoresSessionsWorkflowsAndGeneratedFilesAfterRestart() throws Exception {
        Path state = temporaryDirectory.resolve("state");
        AgentSession session = AgentSession.open("as-1", PRINCIPAL, Instant.parse("2026-08-18T12:00:00Z"),
            Duration.ofHours(1));
        ScenarioWorkflow workflow = ScenarioWorkflow.create("wf-1", session.id(), PRINCIPAL);
        session.addWorkflow(0, workflow.id(), 4);

        try (AtomicCoordinationStateRepository repository = repository(state, 1_000_000, 10, 4)) {
            repository.createSession(AgentSession.open("as-empty", PRINCIPAL,
                Instant.parse("2026-08-18T12:00:00Z"), Duration.ofHours(1)));
            repository.createSession(AgentSession.open("as-other", new PrincipalKey(
                URI.create("https://issuer.example"), "other"), Instant.parse("2026-08-18T12:00:00Z"),
                Duration.ofHours(1)));
            repository.createSession(session);
            repository.createWorkflow(session, workflow);
            workflow.cancel(0);
            repository.saveWorkflow(workflow, 0, List.of(Map.of(
                "path", "seed/init.sql", "content", "select 1;", "sha256", "sha256:file")));
            assertThat(repository.countOpenSessions(PRINCIPAL)).isEqualTo(2);
        }

        try (AtomicCoordinationStateRepository restarted = repository(state, 1_000_000, 10, 4)) {
            assertThat(restarted.findSession("as-1")).get().satisfies(restored -> {
                assertThat(restored.workflowIds()).containsExactly("wf-1");
                assertThat(restored.revision()).isEqualTo(1);
            });
            assertThat(restarted.findWorkflow("wf-1")).get().satisfies(restored -> {
                assertThat(restored.revision()).isEqualTo(1);
                assertThat(restored.state().name()).isEqualTo("CANCELLED");
            });
            assertThat(restarted.findGeneratedFiles("wf-1"))
                .containsExactly(Map.of("path", "seed/init.sql", "content", "select 1;", "sha256", "sha256:file"));
        }
    }

    @Test
    void rejectsCorruptionAndASecondWriterWithoutDeletingEvidence() throws Exception {
        Path corrupt = temporaryDirectory.resolve("corrupt");
        Files.createDirectories(corrupt);
        Files.writeString(corrupt.resolve("state.json"), "not-json");
        assertThatThrownBy(() -> repository(corrupt, 1_000_000, 10, 4))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MCP_STATE_CORRUPT");
        assertThat(Files.readString(corrupt.resolve("state.json"))).isEqualTo("not-json");

        Path wrongShape = temporaryDirectory.resolve("wrong-shape");
        Files.createDirectories(wrongShape);
        Files.writeString(wrongShape.resolve("state.json"), "[]");
        assertThatThrownBy(() -> repository(wrongShape, 1_000_000, 10, 4))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MCP_STATE_CORRUPT");

        Path locked = temporaryDirectory.resolve("locked");
        try (AtomicCoordinationStateRepository first = repository(locked, 1_000_000, 10, 4)) {
            assertThatThrownBy(() -> repository(locked, 1_000_000, 10, 4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP_STATE_LOCKED");
        }
    }

    @Test
    void explicitlyMigratesLegacyReceiptsWithoutInferringMissingOwnerIdentity() throws Exception {
        Path state = temporaryDirectory.resolve("legacy-v1");
        Files.createDirectories(state);
        Files.writeString(state.resolve("state.json"), """
            {
              "schemaVersion": 1,
              "sessions": {},
              "workflows": {},
              "generatedFiles": {},
              "uploadCoordination": {
                "tickets": {
                  "ut-legacy": {
                    "validationReceiptId": "vr-legacy"
                  },
                  "ut-blank": {
                    "validationReceiptId": "vr-blank"
                  }
                },
                "receipts": {
                  "vr-legacy": {
                    "id": "vr-legacy",
                    "scenarioId": "checkout-smoke"
                  },
                  "vr-blank": {
                    "id": "vr-blank",
                    "scenarioId": "checkout-smoke-copy",
                    "scenarioName": " "
                  }
                },
                "attempts": {}
              }
            }
            """);

        try (AtomicCoordinationStateRepository migrated = repository(state, 1_000_000, 10, 4)) {
            assertThat(migrated.loadUploadCoordination().receipts()).isEmpty();
            assertThat(migrated.loadUploadCoordination().tickets()).isEmpty();
        }

        assertThat(mapper.readTree(state.resolve("state.json").toFile()).path("schemaVersion").asInt())
            .isEqualTo(4);
    }

    @Test
    void legacyMigrationPreservesReceiptsThatAlreadyContainExactOwnerIdentity() throws Exception {
        Path state = temporaryDirectory.resolve("legacy-v1-complete");
        BundleValidationReceipt receipt = new BundleValidationReceipt("vr-complete", PRINCIPAL,
            UploadWorkflowBinding.direct(), new SourceMetadata("git@example/repo", "a".repeat(40),
            "scenarios/bundles/checkout-smoke", SourceVerification.CLIENT_ASSERTED),
            new BundleFileManifest(List.of(new BundleFileManifestEntry("scenario.yaml", 4, SHA))),
            SHA, SHA, "checkout-smoke", "Checkout smoke", Instant.parse("2026-08-18T12:00:00Z"));
        try (AtomicCoordinationStateRepository current = repository(state, 1_000_000, 10, 4)) {
            current.saveUploadCoordination(new UploadCoordinationSnapshot(
                Map.of(), Map.of(receipt.id(), receipt), Map.of()));
        }
        ObjectNode legacy = (ObjectNode) mapper.readTree(state.resolve("state.json").toFile());
        legacy.put("schemaVersion", 1);
        mapper.writeValue(state.resolve("state.json").toFile(), legacy);

        try (AtomicCoordinationStateRepository migrated = repository(state, 1_000_000, 10, 4)) {
            assertThat(migrated.loadUploadCoordination().receipts()).containsEntry(receipt.id(), receipt);
        }
        assertThat(mapper.readTree(state.resolve("state.json").toFile()).path("schemaVersion").asInt())
            .isEqualTo(4);
    }

    @Test
    void versionTwoMigrationInvalidatesCapabilitylessTicketsAndTheirPublicationAttempts() throws Exception {
        Path state = temporaryDirectory.resolve("legacy-v2-upload-authentication");
        Files.createDirectories(state);
        ObjectNode root = emptyState(2);
        ObjectNode coordination = (ObjectNode) root.path("uploadCoordination");
        ((ObjectNode) coordination.path("tickets")).putObject("up-legacy")
            .put("attemptId", "pa-legacy");
        ((ObjectNode) coordination.path("attempts")).putObject("pa-legacy")
            .put("state", "PREPARED");
        mapper.writeValue(state.resolve("state.json").toFile(), root);

        try (AtomicCoordinationStateRepository migrated = repository(state, 1_000_000, 10, 4)) {
            assertThat(migrated.loadUploadCoordination().tickets()).isEmpty();
            assertThat(migrated.loadUploadCoordination().attempts()).isEmpty();
        }
        assertThat(mapper.readTree(state.resolve("state.json").toFile()).path("schemaVersion").asInt())
            .isEqualTo(4);
    }

    @Test
    void versionThreeMigrationRetiresUnboundWorkflowTicketsButPreservesHistoricalEvidence() throws Exception {
        Path path = temporaryDirectory.resolve("legacy-v3-generation");
        var binding = new UploadWorkflowBinding(io.pockethive.mcp.application.UploadWorkflowMode.WORKFLOW,
            "wf-1", 1, SHA, new io.pockethive.mcp.domain.CapabilityFingerprint(SHA, Instant.EPOCH));
        var source = new SourceMetadata("git@example/repo", "a".repeat(40), "scenarios/safe", SourceVerification.CLIENT_ASSERTED);
        var manifest = new BundleFileManifest(List.of(new BundleFileManifestEntry("scenario.yaml", 4, SHA)));
        BundleValidationReceipt receipt = new BundleValidationReceipt("vr-old", PRINCIPAL, binding,
            source, manifest, SHA, SHA, "safe", "Safe", Instant.EPOCH);
        var direct = new io.pockethive.mcp.application.ValidationUploadTicket("uv-direct", PRINCIPAL,
            UploadWorkflowBinding.direct(), source, manifest, SHA, Instant.EPOCH.plusSeconds(60));
        var workflow = new io.pockethive.mcp.application.ValidationUploadTicket("uv-workflow", PRINCIPAL,
            binding, source, manifest, SHA, Instant.EPOCH.plusSeconds(60));
        var attempt = new io.pockethive.mcp.application.PublicationAttempt("pa-old", PRINCIPAL,
            io.pockethive.mcp.application.PublicationMode.CREATE, "safe", SHA, Instant.EPOCH);
        attempt.receiving();
        attempt.verified();
        attempt.ownerCallInFlight();
        attempt.succeeded(Map.of("id", "safe"));
        try (AtomicCoordinationStateRepository current = repository(path, 1_000_000, 10, 4)) {
            current.saveUploadCoordination(new UploadCoordinationSnapshot(Map.of(
                direct.id(), io.pockethive.mcp.application.UploadTicketSnapshot.from(direct),
                workflow.id(), io.pockethive.mcp.application.UploadTicketSnapshot.from(workflow)),
                Map.of(receipt.id(), receipt), Map.of(attempt.id(), attempt.snapshot())));
        }
        ObjectNode root = (ObjectNode) mapper.readTree(path.resolve("state.json").toFile());
        root.put("schemaVersion", 3);
        ObjectNode legacyBinding = (ObjectNode) root.path("uploadCoordination").path("receipts").path("vr-old").path("workflowBinding");
        legacyBinding.remove(List.of("preparedRevision", "generatedFileSetDigest", "capabilityFingerprint"));
        mapper.writeValue(path.resolve("state.json").toFile(), root);
        try (AtomicCoordinationStateRepository migrated = repository(path, 1_000_000, 10, 4)) {
            var snapshot = migrated.loadUploadCoordination();
            assertThat(snapshot.tickets()).containsOnlyKeys("uv-direct");
            assertThat(snapshot.tickets().get("uv-direct")).isEqualTo(io.pockethive.mcp.application.UploadTicketSnapshot.from(direct));
            assertThat(snapshot.attempts()).containsEntry(attempt.id(), attempt.snapshot());
            assertThat(snapshot.receipts().get(receipt.id()).scenarioName()).isEqualTo("Safe");
            assertThat(snapshot.receipts().get(receipt.id()).archiveDigest()).isEqualTo(SHA);
            assertThat(snapshot.receipts().get(receipt.id()).workflowBinding()).isEqualTo(
                new UploadWorkflowBinding(io.pockethive.mcp.application.UploadWorkflowMode.LEGACY_WORKFLOW, "wf-1", 0, null, null));
        }
        assertThat(mapper.readTree(path.resolve("state.json").toFile()).path("schemaVersion").asInt()).isEqualTo(4);
    }

    @Test
    void versionThreeMigrationRejectsMalformedBindingCollections() throws Exception {
        for (String collection : List.of("tickets", "receipts")) {
            Path path = temporaryDirectory.resolve("legacy-v3-malformed-" + collection);
            Files.createDirectories(path);
            ObjectNode root = emptyState(3);
            ((ObjectNode) root.path("uploadCoordination")).set(collection, mapper.createArrayNode());
            mapper.writeValue(path.resolve("state.json").toFile(), root);
            assertThatThrownBy(() -> repository(path, 1_000_000, 10, 4))
                .hasMessageContaining("MCP_STATE_CORRUPT");
        }
    }

    @Test
    void legacyMigrationRejectsMalformedCoordinationCollectionsAndUnsupportedVersions() throws Exception {
        for (String malformedField : List.of("receipts", "tickets")) {
            Path state = temporaryDirectory.resolve("legacy-malformed-" + malformedField);
            Files.createDirectories(state);
            ObjectNode root = emptyState(1);
            ((ObjectNode) root.path("uploadCoordination")).set(malformedField, mapper.createArrayNode());
            mapper.writeValue(state.resolve("state.json").toFile(), root);
            assertThatThrownBy(() -> repository(state, 1_000_000, 10, 4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP_STATE_CORRUPT");
        }

        Path malformedAttempts = temporaryDirectory.resolve("legacy-v2-malformed-attempts");
        Files.createDirectories(malformedAttempts);
        ObjectNode legacyV2 = emptyState(2);
        ((ObjectNode) legacyV2.path("uploadCoordination")).set("attempts", mapper.createArrayNode());
        mapper.writeValue(malformedAttempts.resolve("state.json").toFile(), legacyV2);
        assertThatThrownBy(() -> repository(malformedAttempts, 1_000_000, 10, 4))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MCP_STATE_CORRUPT");

        Path unsupported = temporaryDirectory.resolve("unsupported-version");
        Files.createDirectories(unsupported);
        mapper.writeValue(unsupported.resolve("state.json").toFile(), emptyState(5));
        assertThatThrownBy(() -> repository(unsupported, 1_000_000, 10, 4))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MCP_STATE_CORRUPT");
    }

    @Test
    void quotaFailuresLeaveThePreviousAtomicStateUnchanged() {
        Path state = temporaryDirectory.resolve("quota");
        try (AtomicCoordinationStateRepository repository = repository(state, 700, 1, 1)) {
            AgentSession first = AgentSession.open("as-1", PRINCIPAL, Instant.now(), Duration.ofHours(1));
            repository.createSession(first);
            assertThatThrownBy(() -> repository.createSession(
                AgentSession.open("as-2", PRINCIPAL, Instant.now(), Duration.ofHours(1))))
                .isInstanceOf(ToolExecutionException.class)
                .extracting(error -> ((ToolExecutionException) error).code())
                .isEqualTo("AGENT_SESSION_LIMIT_REACHED");
            assertThat(repository.findSession("as-1")).isPresent();
            assertThat(repository.findSession("as-2")).isEmpty();
        }
    }

    @Test
    void expiryAndTerminalRetentionAreExplicitAndRemoveOnlyOwnedCoordinationState() {
        Path state = temporaryDirectory.resolve("retention");
        Instant created = Instant.parse("2026-08-18T12:00:00Z");
        try (AtomicCoordinationStateRepository repository = repository(state, 1_000_000, 10, 4)) {
            AgentSession session = AgentSession.open("as-expiring", PRINCIPAL, created, Duration.ofHours(1));
            ScenarioWorkflow workflow = ScenarioWorkflow.create("wf-expiring", session.id(), PRINCIPAL);
            session.addWorkflow(0, workflow.id(), 4);
            repository.createSession(session);
            repository.createWorkflow(session, workflow);

            repository.maintainSessions(created.plus(Duration.ofHours(1)), Duration.ofMinutes(30));
            assertThat(repository.findSession(session.id())).get()
                .extracting(restored -> restored.state().name()).isEqualTo("EXPIRED");
            assertThat(repository.findWorkflow(workflow.id())).isPresent();

            repository.maintainSessions(created.plus(Duration.ofMinutes(91)), Duration.ofMinutes(30));
            assertThat(repository.findSession(session.id())).isEmpty();
            assertThat(repository.findWorkflow(workflow.id())).isEmpty();
        }
    }

    private AtomicCoordinationStateRepository repository(Path state, long maxBytes,
                                                          int maxOpenSessions,
                                                          int maxPerPrincipal) {
        return new AtomicCoordinationStateRepository(mapper, McpStateMode.FILE,
            state, maxBytes, maxOpenSessions, maxPerPrincipal);
    }

    private ObjectNode emptyState(int schemaVersion) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", schemaVersion);
        root.set("sessions", mapper.createObjectNode());
        root.set("workflows", mapper.createObjectNode());
        root.set("generatedFiles", mapper.createObjectNode());
        ObjectNode uploadCoordination = mapper.createObjectNode();
        uploadCoordination.set("tickets", mapper.createObjectNode());
        uploadCoordination.set("receipts", mapper.createObjectNode());
        uploadCoordination.set("attempts", mapper.createObjectNode());
        root.set("uploadCoordination", uploadCoordination);
        return root;
    }
}
