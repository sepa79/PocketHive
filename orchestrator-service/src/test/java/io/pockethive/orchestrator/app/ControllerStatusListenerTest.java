package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.domain.Swarm;
import io.pockethive.orchestrator.domain.SwarmStore;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.lifecycle.ControllerState;
import io.pockethive.swarm.model.lifecycle.Health;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import org.junit.jupiter.api.Test;

class ControllerStatusListenerTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void fullStatusUpdatesOnlyCanonicalObservationAxes() {
    SwarmStore store = new SwarmStore();
    Swarm swarm = new Swarm("sw1", "inst1", "c1", "run-1");
    store.register(swarm);
    ControlPlaneStatusRequestPublisher requests = mock(ControlPlaneStatusRequestPublisher.class);
    SwarmSignalListener signals = mock(SwarmSignalListener.class);
    ControllerStatusListener listener = listener(store, requests, signals);

    String json = status("status-full", """
        {
          "controllerState":"READY",
          "workloadState":"RUNNING",
          "health":"HEALTHY",
          "sutId":"wiremock-proxy-local",
          "networkMode":"DIRECT",
          "networkProfileId":null
        }
        """);
    listener.handle(json, "event.metric.status-full.sw1.swarm-controller.inst1");

    assertThat(swarm.getControllerState()).isEqualTo(ControllerState.READY);
    assertThat(swarm.getWorkloadState()).isEqualTo(WorkloadState.RUNNING);
    assertThat(swarm.getHealth()).isEqualTo(Health.HEALTHY);
    assertThat(swarm.getSutId()).isEqualTo("wiremock-proxy-local");
    assertThat(swarm.getNetworkMode()).isEqualTo(NetworkMode.DIRECT);
    verify(signals).handleControllerStatusFull(
        eq("event.metric.status-full.sw1.swarm-controller.inst1"),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void deltaWithoutBaselineRequestsFullSnapshotAndDoesNotInventState() {
    SwarmStore store = new SwarmStore();
    store.register(new Swarm("sw1", "inst1", "c1", "run-1"));
    ControlPlaneStatusRequestPublisher requests = mock(ControlPlaneStatusRequestPublisher.class);
    SwarmSignalListener signals = mock(SwarmSignalListener.class);
    ControllerStatusListener listener = listener(store, requests, signals);

    listener.handle(status("status-delta", """
        {"controllerState":"READY","workloadState":"STOPPED","health":"HEALTHY"}
        """), "event.metric.status-delta.sw1.swarm-controller.inst1");

    verify(requests).requestStatusForSwarm(eq("sw1"), anyString(), anyString());
    assertThat(store.find("sw1").orElseThrow().getControllerState())
        .isEqualTo(ControllerState.PROVISIONING);
  }

  @Test
  void statusNeverReconstructsAnUnregisteredSwarm() {
    SwarmStore store = new SwarmStore();
    ControlPlaneStatusRequestPublisher requests = mock(ControlPlaneStatusRequestPublisher.class);
    SwarmSignalListener signals = mock(SwarmSignalListener.class);
    ControllerStatusListener listener = listener(store, requests, signals);

    listener.handle(status("status-full", """
        {"controllerState":"READY","workloadState":"STOPPED","health":"HEALTHY"}
        """), "event.metric.status-full.sw1.swarm-controller.inst1");

    assertThat(store.find("sw1")).isEmpty();
    verifyNoInteractions(requests, signals);
  }

  @Test
  void rejectsMissingTransportIdentityWithoutThrowing() {
    SwarmStore store = mock(SwarmStore.class);
    ControlPlaneStatusRequestPublisher requests = mock(ControlPlaneStatusRequestPublisher.class);
    SwarmSignalListener signals = mock(SwarmSignalListener.class);
    ControllerStatusListener listener = listener(store, requests, signals);

    assertThatCode(() -> listener.handle("{}", " ")).doesNotThrowAnyException();
    assertThatCode(() -> listener.handle(" ", "event.metric.status-full.sw1.swarm-controller.inst1"))
        .doesNotThrowAnyException();
    verifyNoInteractions(store, requests, signals);
  }

  private ControllerStatusListener listener(
      SwarmStore store,
      ControlPlaneStatusRequestPublisher requests,
      SwarmSignalListener signals) {
    return new ControllerStatusListener(store, mapper, requests, signals, HiveJournal.noop());
  }

  private String status(String type, String context) {
    return """
        {
          "timestamp":"2026-07-22T12:00:00Z",
          "version":"2",
          "kind":"metric",
          "type":"%s",
          "origin":"inst1",
          "scope":{"swarmId":"sw1","role":"swarm-controller","instance":"inst1"},
          "correlationId":null,
          "idempotencyKey":null,
          "runtime":{"templateId":"tpl-1","runId":"run-1"},
          "data":{"config":{},"io":{},"ioState":{},"context":%s}
        }
        """.formatted(type, context);
  }
}
