package io.pockethive.swarmcontroller;

import java.util.Map;

public record ControlSignal(
    String correlationId,
    String idempotencyKey,
    String signal,
    Map<String, String> scope
) {}
