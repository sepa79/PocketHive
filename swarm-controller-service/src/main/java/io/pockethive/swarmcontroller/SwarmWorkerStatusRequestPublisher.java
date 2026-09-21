package io.pockethive.swarmcontroller;

import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.ControlSignals;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Responsibility: Construct and publish a canonical status request for one identified worker.
 * Must not: Track readiness, consume status events, or decide lifecycle outcomes.
 * Contract: Preserve active diagnostic identifiers or generate explicit identifiers for each request.
 */
final class SwarmWorkerStatusRequestPublisher implements WorkerStatusRequestCallback {

  private static final Logger log = LoggerFactory.getLogger(SwarmWorkerStatusRequestPublisher.class);
  private static final String CORRELATION_MDC_KEY = "correlation_id";
  private static final String IDEMPOTENCY_MDC_KEY = "idempotency_key";
  private static final String GENERATED_ID_PREFIX = "status-request:";

  private final ControlPlanePublisher publisher;
  private final String swarmId;
  private final String controllerInstanceId;

  SwarmWorkerStatusRequestPublisher(
      ControlPlanePublisher publisher,
      String swarmId,
      String controllerInstanceId) {
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.swarmId = requireText(swarmId, "swarmId");
    this.controllerInstanceId = requireText(controllerInstanceId, "controllerInstanceId");
  }

  @Override
  public void requestStatus(String role, String instance, String reason) {
    String routingKey = ControlPlaneRouting.signal(
        ControlPlaneSignals.STATUS_REQUEST, swarmId, role, instance);
    String correlationId = diagnosticId(CORRELATION_MDC_KEY);
    String idempotencyKey = diagnosticId(IDEMPOTENCY_MDC_KEY);
    ControlScope target = ControlScope.forInstance(swarmId, role, instance);
    ControlSignal signal = ControlSignals.statusRequest(
        controllerInstanceId,
        target,
        correlationId,
        idempotencyKey);
    log.info(
        "[CTRL] SEND rk={} inst={} correlationId={} (reason={})",
        routingKey,
        controllerInstanceId,
        correlationId,
        reason);
    publisher.publishSignal(new SignalMessage(routingKey, signal));
  }

  private static String diagnosticId(String mdcKey) {
    String value = MDC.get(mdcKey);
    return value != null && !value.isBlank() ? value : GENERATED_ID_PREFIX + UUID.randomUUID();
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
