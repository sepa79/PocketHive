package io.pockethive.mcp.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.mcp.application.ToolExecutionException;
import io.pockethive.mcp.config.PocketHiveMcpProperties;
import io.pockethive.mcp.domain.AgentSession;
import io.pockethive.mcp.domain.PrincipalKey;
import io.pockethive.mcp.domain.ScenarioWorkflow;
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

    @TempDir Path temporaryDirectory;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

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
            repository.saveWorkflow(workflow, List.of(Map.of(
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

        Path locked = temporaryDirectory.resolve("locked");
        try (AtomicCoordinationStateRepository first = repository(locked, 1_000_000, 10, 4)) {
            assertThatThrownBy(() -> repository(locked, 1_000_000, 10, 4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP_STATE_LOCKED");
        }
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
        return new AtomicCoordinationStateRepository(mapper, PocketHiveMcpProperties.StateMode.FILE,
            state, maxBytes, maxOpenSessions, maxPerPrincipal);
    }
}
