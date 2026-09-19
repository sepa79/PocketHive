package io.pockethive.mcp.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Responsibility: Model the AgentSession MCP domain concept and enforce its local invariants.
 * Must not: Access transport, configuration, or infrastructure adapters.
 * Contract: docs/mcp/README.md.
 */

public final class AgentSession {
    private final String id;
    private final PrincipalKey principal;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final List<String> workflowIds = new ArrayList<>();
    private AgentSessionState state = AgentSessionState.OPEN;
    private Instant closedAt;
    private long revision;

    private AgentSession(String id, PrincipalKey principal, Instant createdAt, Duration ttl) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        this.id = id.trim();
        this.principal = Objects.requireNonNull(principal, "principal");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = createdAt.plus(ttl);
    }

    public static AgentSession open(String id, PrincipalKey principal, Instant createdAt, Duration ttl) {
        return new AgentSession(id, principal, createdAt, ttl);
    }

    public static AgentSession restore(AgentSessionSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        AgentSession session = new AgentSession(snapshot.id(), snapshot.principal(), snapshot.createdAt(),
            Duration.between(snapshot.createdAt(), snapshot.expiresAt()));
        session.workflowIds.addAll(snapshot.workflowIds());
        session.state = snapshot.state();
        session.closedAt = snapshot.closedAt();
        session.revision = snapshot.revision();
        return session;
    }

    public AgentSessionSnapshot snapshot() {
        return new AgentSessionSnapshot(id, principal, createdAt, expiresAt, workflowIds, state, closedAt, revision);
    }

    public void addWorkflow(long expectedRevision, String workflowId, int maximumWorkflows) {
        requireRevision(expectedRevision);
        requireOpen();
        if (workflowId == null || workflowId.isBlank()) {
            throw new IllegalArgumentException("workflowId must not be blank");
        }
        String normalized = workflowId.trim();
        if (workflowIds.contains(normalized)) {
            throw new WorkflowRuleViolation("SESSION_WORKFLOW_ALREADY_EXISTS");
        }
        if (maximumWorkflows < 1 || workflowIds.size() >= maximumWorkflows) {
            throw new WorkflowRuleViolation("SESSION_WORKFLOW_LIMIT_REACHED");
        }
        workflowIds.add(normalized);
        revision++;
    }

    public void close(long expectedRevision, Instant at) {
        requireRevision(expectedRevision);
        requireOpen();
        closedAt = Objects.requireNonNull(at, "at");
        state = AgentSessionState.CLOSED;
        revision++;
    }

    public void expireAt(Instant now) {
        Objects.requireNonNull(now, "now");
        if (state == AgentSessionState.OPEN && !now.isBefore(expiresAt)) {
            state = AgentSessionState.EXPIRED;
            revision++;
        }
    }

    private void requireRevision(long expectedRevision) {
        if (expectedRevision != revision) {
            throw new WorkflowRuleViolation("SESSION_VERSION_CONFLICT");
        }
    }

    private void requireOpen() {
        if (state != AgentSessionState.OPEN) {
            throw new WorkflowRuleViolation("SESSION_NOT_OPEN");
        }
    }

    public String id() {
        return id;
    }

    public PrincipalKey principal() {
        return principal;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public AgentSessionState state() {
        return state;
    }

    public Instant closedAt() {
        return closedAt;
    }

    public long revision() {
        return revision;
    }

    public List<String> workflowIds() {
        return List.copyOf(workflowIds);
    }
}
