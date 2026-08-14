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
import io.pockethive.control.ControlScope;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ControllerStatusListenerTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final ControlPlaneCodec codec = ControlPlaneCodec.create();

  @Test
  void fullStatusUpdatesOnlyCanonicalObservationAxes() {
    SwarmStore store = new SwarmStore();
    Swarm swarm = new Swarm("sw1", "inst1", "c1", "run-1", NetworkMode.DIRECT);
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
    store.register(new Swarm("sw1", "inst1", "c1", "run-1", NetworkMode.DIRECT));
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
    return new ControllerStatusListener(
        store,
        mapper,
        io.pockethive.controlplane.codec.ControlPlaneCodec.create(),
        requests,
        signals,
        HiveJournal.noop());
  }

  private String status(String type, String contextJson) {
    try {
      var contextNode = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(contextJson);
      contextNode.putIfAbsent("startupReady", mapper.getNodeFactory().booleanNode(true));
      contextNode.putIfAbsent("watermarkAt", mapper.getNodeFactory().textNode("2026-07-22T12:00:00Z"));
      contextNode.putIfAbsent("controllerState", mapper.getNodeFactory().textNode("READY"));
      contextNode.putIfAbsent("workloadState", mapper.getNodeFactory().textNode("STOPPED"));
      contextNode.putIfAbsent("health", mapper.getNodeFactory().textNode("HEALTHY"));
      Map<String, Object> context = mapper.convertValue(contextNode, Map.class);
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("context", context);
      if ("status-full".equals(type)) {
        context.put("startupArtifactSha256", "a".repeat(64));
        context.put("expectedWorkers", java.util.List.of());
        context.put("workers", java.util.List.of());
        data.put("config", Map.of());
        data.put("startedAt", "2026-07-22T12:00:00Z");
        data.put("io", Map.of());
        data.put("ioState", Map.of());
      } else {
        data.put("ioState", Map.of());
      }
      StatusMetric status = new StatusMetric(
          Instant.parse("2026-07-22T12:00:00Z"), "2", "metric", type, "inst1",
          new ControlScope("sw1", "swarm-controller", "inst1"), null, null,
          Map.of("templateId", "tpl-1", "runId", "run-1"), data);
      return codec.encode(status, "event.metric." + type + ".sw1.swarm-controller.inst1");
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
