package io.pockethive.swarmcontroller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

class SwarmWorkerStatusRequestPublisherTest {

  private static final String SWARM_ID = "swarm-1";
  private static final String CONTROLLER_INSTANCE = "controller-1";

  @AfterEach
  void clearDiagnosticContext() {
    MDC.clear();
  }

  @Test
  void publishesCanonicalInstanceRequestWithActiveDiagnosticIdentifiers() {
    ControlPlanePublisher publisher = mock(ControlPlanePublisher.class);
    SwarmWorkerStatusRequestPublisher requests = new SwarmWorkerStatusRequestPublisher(
        publisher, SWARM_ID, CONTROLLER_INSTANCE);
    MDC.put("correlation_id", "correlation-1");
    MDC.put("idempotency_key", "idempotency-1");

    requests.requestStatus("generator", "generator-1", "stale-heartbeat");

    SignalMessage message = capturedMessage(publisher);
    assertThat(message.routingKey()).isEqualTo(ControlPlaneRouting.signal(
        ControlPlaneSignals.STATUS_REQUEST, SWARM_ID, "generator", "generator-1"));
    ControlSignal signal = (ControlSignal) message.payload();
    assertThat(signal.type()).isEqualTo(ControlPlaneSignals.STATUS_REQUEST);
    assertThat(signal.origin()).isEqualTo(CONTROLLER_INSTANCE);
    assertThat(signal.scope()).isEqualTo(
        ControlScope.forInstance(SWARM_ID, "generator", "generator-1"));
    assertThat(signal.correlationId()).isEqualTo("correlation-1");
    assertThat(signal.idempotencyKey()).isEqualTo("idempotency-1");
  }

  @Test
  void generatesExplicitIdentifiersWhenNoDiagnosticContextExists() {
    ControlPlanePublisher publisher = mock(ControlPlanePublisher.class);
    SwarmWorkerStatusRequestPublisher requests = new SwarmWorkerStatusRequestPublisher(
        publisher, SWARM_ID, CONTROLLER_INSTANCE);

    requests.requestStatus("processor", "processor-1", "missing-heartbeat");

    ControlSignal signal = (ControlSignal) capturedMessage(publisher).payload();
    assertThat(signal.correlationId()).startsWith("status-request:");
    assertThat(signal.idempotencyKey()).startsWith("status-request:");
    assertThat(signal.correlationId()).isNotEqualTo(signal.idempotencyKey());
  }

  private static SignalMessage capturedMessage(ControlPlanePublisher publisher) {
    ArgumentCaptor<SignalMessage> message = ArgumentCaptor.forClass(SignalMessage.class);
    verify(publisher).publishSignal(message.capture());
    return message.getValue();
  }
}
