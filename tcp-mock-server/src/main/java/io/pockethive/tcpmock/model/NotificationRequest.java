package io.pockethive.tcpmock.model;

/**
 * Responsibility: carry the existing mock UI notification creation request.
 * Must not: validate or decide retention, read state or persistence.
 * Contract: RESP-TCP-MOCK-NOTIFICATIONS — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-notifications.
 */
public final class NotificationRequest {
    public String message;
    public String type;
    public boolean persistent;
}
