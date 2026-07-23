package io.pockethive.control;

import java.time.Instant;

/** Canonical Java boundary shared by every control-plane envelope kind. */
public sealed interface ControlPlaneEnvelope permits
    AlertMessage,
    CommandOutcome,
    CommandResult,
    ControlSignal,
    JournalEvent,
    StatusMetric {

  Instant timestamp();

  String version();

  String kind();

  String type();

  String origin();

  ControlScope scope();

  String correlationId();

  String idempotencyKey();

  Object data();
}
