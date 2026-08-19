package io.pockethive.mcp.application;

public enum PublicationAttemptState {
    PREPARED,
    RECEIVING,
    VERIFIED,
    OWNER_CALL_IN_FLIGHT,
    SUCCEEDED,
    FAILED,
    AMBIGUOUS
}
