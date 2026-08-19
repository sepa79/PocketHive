package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.PrincipalKey;
import java.time.Instant;
import java.util.Objects;

public final class PublicationAttempt {
    private final String id;
    private final PrincipalKey principal;
    private final PublicationMode mode;
    private final String scenarioId;
    private final String expectedContentDigest;
    private final Instant createdAt;
    private PublicationAttemptState state = PublicationAttemptState.PREPARED;
    private Object ownerResult;

    public PublicationAttempt(String id, PrincipalKey principal, PublicationMode mode,
                              String scenarioId, String expectedContentDigest, Instant createdAt) {
        this.id = Objects.requireNonNull(id);
        this.principal = Objects.requireNonNull(principal);
        this.mode = Objects.requireNonNull(mode);
        this.scenarioId = scenarioId;
        this.expectedContentDigest = Objects.requireNonNull(expectedContentDigest);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    public static PublicationAttempt restore(PublicationAttemptSnapshot snapshot) {
        PublicationAttempt attempt = new PublicationAttempt(snapshot.id(), snapshot.principal(), snapshot.mode(),
            snapshot.scenarioId(), snapshot.expectedContentDigest(), snapshot.createdAt());
        attempt.state = snapshot.state();
        attempt.ownerResult = snapshot.ownerResult();
        return attempt;
    }

    public PublicationAttemptSnapshot snapshot() {
        return new PublicationAttemptSnapshot(id, principal, mode, scenarioId, expectedContentDigest,
            createdAt, state, ownerResult);
    }

    public void receiving() { transition(PublicationAttemptState.PREPARED, PublicationAttemptState.RECEIVING); }
    public void verified() { transition(PublicationAttemptState.RECEIVING, PublicationAttemptState.VERIFIED); }
    public void ownerCallInFlight() { transition(PublicationAttemptState.VERIFIED, PublicationAttemptState.OWNER_CALL_IN_FLIGHT); }
    public void succeeded(Object result) {
        if (state != PublicationAttemptState.OWNER_CALL_IN_FLIGHT && state != PublicationAttemptState.AMBIGUOUS) {
            throw new IllegalStateException("PUBLICATION_ATTEMPT_TRANSITION_INVALID");
        }
        ownerResult = Objects.requireNonNull(result);
        state = PublicationAttemptState.SUCCEEDED;
    }
    public void failed() {
        if (state != PublicationAttemptState.RECEIVING && state != PublicationAttemptState.VERIFIED
            && state != PublicationAttemptState.OWNER_CALL_IN_FLIGHT) {
            throw new IllegalStateException("PUBLICATION_ATTEMPT_TRANSITION_INVALID");
        }
        state = PublicationAttemptState.FAILED;
    }
    public void ambiguous() { transition(PublicationAttemptState.OWNER_CALL_IN_FLIGHT, PublicationAttemptState.AMBIGUOUS); }

    private void transition(PublicationAttemptState expected, PublicationAttemptState next) {
        if (state != expected) {
            throw new IllegalStateException("PUBLICATION_ATTEMPT_TRANSITION_INVALID");
        }
        state = next;
    }

    public String id() { return id; }
    public PrincipalKey principal() { return principal; }
    public PublicationMode mode() { return mode; }
    public String scenarioId() { return scenarioId; }
    public String expectedContentDigest() { return expectedContentDigest; }
    public Instant createdAt() { return createdAt; }
    public PublicationAttemptState state() { return state; }
    public Object ownerResult() { return ownerResult; }
}
