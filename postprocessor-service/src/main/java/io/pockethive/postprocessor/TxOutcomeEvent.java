package io.pockethive.postprocessor;

import java.util.Map;
import java.util.Objects;

record TxOutcomeEvent(
    String eventTime,
    String swarmId,
    String sinkRole,
    String sinkInstance,
    String traceId,
    String callId,
    int processorStatus,
    int processorSuccess,
    long processorDurationMs,
    String businessCode,
    int businessSuccess,
    Map<String, String> dimensions
) {

  TxOutcomeEvent {
    eventTime = Objects.requireNonNull(eventTime, "eventTime");
    swarmId = Objects.requireNonNull(swarmId, "swarmId");
    sinkRole = Objects.requireNonNull(sinkRole, "sinkRole");
    sinkInstance = Objects.requireNonNull(sinkInstance, "sinkInstance");
    traceId = traceId == null ? "" : traceId;
    callId = callId == null ? "" : callId;
    businessCode = businessCode == null ? "" : businessCode;
    processorDurationMs = Math.max(0L, processorDurationMs);
    processorSuccess = processorSuccess == 0 ? 0 : 1;
    businessSuccess = businessSuccess == 0 ? 0 : 1;
    dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
  }
}
