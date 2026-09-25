package io.pockethive.tcpmock.model;

import java.time.Instant;

/**
 * Responsibility: expose a read-only snapshot of one mock UI notification.
 * Must not: own or mutate notification feed state.
 * Contract: RESP-TCP-MOCK-NOTIFICATIONS — docs/architecture/runtime-responsibilities.md#resp-tcp-mock-notifications.
 */
public record Notification(long id, String message, String type, Instant timestamp, boolean read) {}
