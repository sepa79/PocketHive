package io.pockethive.orchestrator.domain;

import java.time.Instant;

/**
 * Base type for control-plane confirmations.
 */
public sealed interface Confirmation permits ReadyConfirmation, ErrorConfirmation {
    String result();
    String signal();
    String swarmId();
    String role();
    String instance();
    String correlationId();
    String idempotencyKey();
    Instant timestamp();
}
