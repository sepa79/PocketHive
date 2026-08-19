package io.pockethive.mcp.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AgentSessionTest {
    private static final PrincipalKey PRINCIPAL =
        new PrincipalKey(URI.create("https://issuer.example"), "user-1");

    @Test
    void oneSessionOwnsMultipleIsolatedWorkflowIds() {
        Instant openedAt = Instant.parse("2026-08-18T12:00:00Z");
        AgentSession session = AgentSession.open(
            " session-1 ", PRINCIPAL, openedAt, Duration.ofHours(2));

        session.addWorkflow(0, "workflow-a", 2);
        session.addWorkflow(1, "workflow-b", 2);

        assertThat(session.workflowIds()).containsExactly("workflow-a", "workflow-b");
        assertThat(session.id()).isEqualTo("session-1");
        assertThat(session.principal()).isEqualTo(PRINCIPAL);
        assertThat(session.createdAt()).isEqualTo(openedAt);
        assertThat(session.expiresAt()).isEqualTo(openedAt.plus(Duration.ofHours(2)));
        assertThat(session.closedAt()).isNull();
        assertThat(session.revision()).isEqualTo(2);
        assertThatThrownBy(() -> session.addWorkflow(2, "workflow-c", 2))
            .isInstanceOf(WorkflowRuleViolation.class)
            .hasMessage("SESSION_WORKFLOW_LIMIT_REACHED");
    }

    @Test
    void duplicateWorkflowStaleRevisionCloseAndExpiryFailExplicitly() {
        Instant openedAt = Instant.parse("2026-08-18T12:00:00Z");
        AgentSession session = AgentSession.open("session-1", PRINCIPAL, openedAt, Duration.ofMinutes(30));
        session.addWorkflow(0, "workflow-a", 3);

        assertThatThrownBy(() -> session.addWorkflow(1, "workflow-a", 3))
            .isInstanceOf(WorkflowRuleViolation.class)
            .hasMessage("SESSION_WORKFLOW_ALREADY_EXISTS");
        assertThatThrownBy(() -> session.addWorkflow(0, "workflow-b", 3))
            .isInstanceOf(WorkflowRuleViolation.class)
            .hasMessage("SESSION_VERSION_CONFLICT");

        session.close(1, openedAt.plusSeconds(60));
        assertThat(session.state()).isEqualTo(AgentSessionState.CLOSED);
        assertThat(session.closedAt()).isEqualTo(openedAt.plusSeconds(60));
        assertThatThrownBy(() -> session.addWorkflow(2, "workflow-b", 3))
            .isInstanceOf(WorkflowRuleViolation.class)
            .hasMessage("SESSION_NOT_OPEN");

        assertThatThrownBy(() -> session.close(1, openedAt.plusSeconds(61)))
            .isInstanceOf(WorkflowRuleViolation.class)
            .hasMessage("SESSION_VERSION_CONFLICT");
        assertThatThrownBy(() -> session.close(2, openedAt.plusSeconds(61)))
            .isInstanceOf(WorkflowRuleViolation.class)
            .hasMessage("SESSION_NOT_OPEN");

        AgentSession expiring = AgentSession.open("session-2", PRINCIPAL, openedAt, Duration.ofMinutes(30));
        expiring.expireAt(openedAt.plus(Duration.ofMinutes(30)));
        assertThat(expiring.state()).isEqualTo(AgentSessionState.EXPIRED);
        assertThatThrownBy(() -> expiring.addWorkflow(1, "workflow-b", 3))
            .isInstanceOf(WorkflowRuleViolation.class)
            .hasMessage("SESSION_NOT_OPEN");
    }

    @Test
    void validatesConstructionWorkflowIdsLimitsAndExpiryBoundary() {
        Instant openedAt = Instant.parse("2026-08-18T12:00:00Z");

        assertThatThrownBy(() -> AgentSession.open(" ", PRINCIPAL, openedAt, Duration.ofMinutes(1)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("id must not be blank");
        assertThatThrownBy(() -> AgentSession.open("id", null, openedAt, Duration.ofMinutes(1)))
            .isInstanceOf(NullPointerException.class).hasMessage("principal");
        assertThatThrownBy(() -> AgentSession.open("id", PRINCIPAL, null, Duration.ofMinutes(1)))
            .isInstanceOf(NullPointerException.class).hasMessage("createdAt");
        assertThatThrownBy(() -> AgentSession.open("id", PRINCIPAL, openedAt, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("ttl must be positive");
        assertThatThrownBy(() -> AgentSession.open("id", PRINCIPAL, openedAt, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("ttl must be positive");
        assertThatThrownBy(() -> AgentSession.open("id", PRINCIPAL, openedAt, Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("ttl must be positive");

        AgentSession session = AgentSession.open("id", PRINCIPAL, openedAt, Duration.ofMinutes(1));
        assertThatThrownBy(() -> session.addWorkflow(0, null, 1))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("workflowId must not be blank");
        assertThatThrownBy(() -> session.addWorkflow(0, " ", 1))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("workflowId must not be blank");
        assertThatThrownBy(() -> session.addWorkflow(0, "workflow", 0))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("SESSION_WORKFLOW_LIMIT_REACHED");
        session.addWorkflow(0, "workflow", 1);
        assertThat(session.workflowIds()).containsExactly("workflow");
        assertThatThrownBy(() -> session.addWorkflow(1, "workflow-2", 1))
            .isInstanceOf(WorkflowRuleViolation.class).hasMessage("SESSION_WORKFLOW_LIMIT_REACHED");
        AgentSession expiring = AgentSession.open("expiring", PRINCIPAL, openedAt, Duration.ofMinutes(1));
        expiring.expireAt(openedAt.plusSeconds(59));
        assertThat(expiring.state()).isEqualTo(AgentSessionState.OPEN);
        assertThat(expiring.revision()).isZero();
        expiring.expireAt(openedAt.plusSeconds(60));
        assertThat(expiring.state()).isEqualTo(AgentSessionState.EXPIRED);
        assertThat(expiring.revision()).isEqualTo(1);
        expiring.expireAt(openedAt.plusSeconds(61));
        assertThat(expiring.revision()).isEqualTo(1);
        assertThatThrownBy(() -> expiring.expireAt(null))
            .isInstanceOf(NullPointerException.class).hasMessage("now");
    }

    @Test
    void restoresEveryPersistedSessionFieldExactly() {
        Instant created = Instant.parse("2026-08-18T12:00:00Z");
        Instant closed = created.plusSeconds(30);
        AgentSessionSnapshot snapshot = new AgentSessionSnapshot(
            "restored", PRINCIPAL, created, created.plusSeconds(60),
            java.util.List.of("workflow-a"), AgentSessionState.CLOSED, closed, 7);

        AgentSession restored = AgentSession.restore(snapshot);

        assertThat(restored.snapshot()).isEqualTo(snapshot);
    }
}
