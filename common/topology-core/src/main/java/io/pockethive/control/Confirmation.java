package io.pockethive.control;

import java.time.Instant;

/**
 * Marker interface for confirmation envelopes exchanged on the control plane.
 */
public sealed interface Confirmation permits ReadyConfirmation, ErrorConfirmation {

    /** Timestamp when the confirmation was generated. */
    Instant ts();

    /** Correlates the confirmation to the originating attempt. */
    String correlationId();

    /** Idempotency key echoed from the originating command. */
    String idempotencyKey();

    /** Signal name that triggered the confirmation. */
    String signal();

    /** Identifies the scope (swarm / role / instance) of the confirmation. */
    ConfirmationScope scope();

    /** Outcome of the command execution. */
    String result();
}

