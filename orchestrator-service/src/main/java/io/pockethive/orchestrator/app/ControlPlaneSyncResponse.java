package io.pockethive.orchestrator.app;

import java.time.Instant;

public record ControlPlaneSyncResponse(
    ControlPlaneSyncService.SyncMode mode,
    String correlationId,
    String idempotencyKey,
    int signalsPublished,
    boolean throttled,
    Instant issuedAt
) {}

