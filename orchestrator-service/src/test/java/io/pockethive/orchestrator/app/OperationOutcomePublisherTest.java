package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.CommandOutcome;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.RuntimeMetadata;
import io.pockethive.swarm.model.lifecycle.SwarmOperation;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import io.pockethive.swarm.model.lifecycle.Target;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class OperationOutcomePublisherTest {

  @Test
  void publishesTheOrchestratorOwnedTerminalOutcomeOnlyOnce() throws Exception {
    ControlPlanePublisher transport = Mockito.mock(ControlPlanePublisher.class);
    OperationOutcomePublisher publisher = new OperationOutcomePublisher(
        transport, "orchestrator-1");
    Instant now = Instant.parse("2026-07-22T12:00:00Z");
    TerminalResult result = new TerminalResult(TerminalStatus.SUCCEEDED, false, Map.of("target", "controller-1"));
    SwarmOperation operation = new SwarmOperation(
        "alpha", OperationType.START, new Target("swarm-controller", "controller-1"),
        new RuntimeMetadata("template-1", "run-1"),
        "corr-1", "idem-1", OperationState.SUCCEEDED,
        now.minusSeconds(2), now.minusSeconds(1), now.plusSeconds(30), now, result);

    publisher.publish(operation);
    publisher.publish(operation);

    ArgumentCaptor<EventMessage> message = ArgumentCaptor.forClass(EventMessage.class);
    verify(transport, times(1)).publishEvent(message.capture());
    assertThat(message.getValue().routingKey())
        .isEqualTo("event.outcome.swarm-start.alpha.orchestrator.orchestrator-1");
    CommandOutcome envelope = (CommandOutcome) message.getValue().payload();
    assertThat(envelope.scope().role()).isEqualTo("orchestrator");
    assertThat(envelope.correlationId()).isEqualTo("corr-1");
    assertThat(envelope.runtime()).containsExactlyInAnyOrderEntriesOf(Map.of("templateId", "template-1", "runId", "run-1"));
    assertThat(envelope.data()).isEqualTo(result);
    verify(transport, times(1)).publishEvent(any(EventMessage.class));
  }

  @Test
  void failedPublicationLeavesTheSameTerminalOutcomeRetryable() {
    ControlPlanePublisher transport = Mockito.mock(ControlPlanePublisher.class);
    OperationOutcomePublisher publisher = new OperationOutcomePublisher(
        transport, "orchestrator-1");
    Instant now = Instant.parse("2026-07-22T12:00:00Z");
    SwarmOperation operation = new SwarmOperation(
        "alpha", OperationType.CREATE, new Target("swarm-controller", "controller-1"),
        new RuntimeMetadata("template-1", "planned-run-1"),
        "corr-retry", "idem-retry", OperationState.FAILED,
        now.minusSeconds(2), now.minusSeconds(1), now.plusSeconds(30), now,
        new TerminalResult(TerminalStatus.FAILED, true, Map.of(
            "target", new Target("swarm-controller", "controller-1"),
            "runtimeIntent", "PRESENT",
            "controllerState", "UNKNOWN",
            "workloadState", "UNKNOWN",
            "startupArtifactSha256", "missing")));
    doThrow(new IllegalStateException("transport unavailable"))
        .doNothing()
        .when(transport).publishEvent(any(EventMessage.class));

    assertThatThrownBy(() -> publisher.publish(operation))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("transport unavailable");
    assertThat(publisher.isPublished("corr-retry")).isFalse();

    assertThat(publisher.publish(operation)).isTrue();
    assertThat(publisher.isPublished("corr-retry")).isTrue();
    verify(transport, times(2)).publishEvent(any(EventMessage.class));
  }
}
