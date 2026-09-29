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
  private final SwarmStatusDiscovery discovery = mock(SwarmStatusDiscovery.class);
  private final HiveJournal journal = mock(HiveJournal.class);

  @Test
  void fullStatusUpdatesOnlyCanonicalObservationAxes() {
    SwarmStore store = new SwarmStore();
    Swarm swarm = new Swarm("sw1", "inst1", "c1", "run-1", NetworkMode.DIRECT);
    store.register(swarm);
    ControlPlaneStatusRequestPublisher requests = mock(ControlPlaneStatusRequestPublisher.class);
    SwarmOperationObservationHandler observations = mock(SwarmOperationObservationHandler.class);
    ControllerStatusListener listener = listener(store, requests, observations);

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
    verify(observations).handleControllerStatusFull(
        eq("sw1"), eq("inst1"),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void deltaWithoutBaselineRequestsFullSnapshotAndDoesNotInventState() {
    SwarmStore store = new SwarmStore();
    store.register(new Swarm("sw1", "inst1", "c1", "run-1", NetworkMode.DIRECT));
    ControlPlaneStatusRequestPublisher requests = mock(ControlPlaneStatusRequestPublisher.class);
    SwarmOperationObservationHandler observations = mock(SwarmOperationObservationHandler.class);
    ControllerStatusListener listener = listener(store, requests, observations);

    listener.handle(status("status-delta", """
        {"controllerState":"READY","workloadState":"STOPPED","health":"HEALTHY"}
        """), "event.metric.status-delta.sw1.swarm-controller.inst1");

    verify(requests).requestStatusForSwarm(eq("sw1"), anyString(), anyString());
    assertThat(store.find("sw1").orElseThrow().getControllerState())
        .isEqualTo(ControllerState.PROVISIONING);
  }

  @Test
  void fullStatusRebuildsCatalogueAfterReset() {
    SwarmStore store = new SwarmStore();
    store.register(new Swarm("sw1", "inst1", "c1", "run-1", NetworkMode.DIRECT));
    var requests = mock(ControlPlaneStatusRequestPublisher.class);
    var observations = mock(SwarmOperationObservationHandler.class);
    var recovered = new Swarm("sw1", "inst1", "c1", "run-1", NetworkMode.DIRECT);
    org.mockito.Mockito.when(discovery.discover(eq("sw1"), eq("inst1"), eq("run-1"), eq("tpl-1"),
        eq(NetworkMode.DIRECT), org.mockito.ArgumentMatchers.any())).thenReturn(recovered);
    var reset = new ControlPlaneSyncService(store, mock(OrchestratorStatusPublisher.class), requests);
    reset.reset();
    assertThat(store.all()).isEmpty();
    listener(store, requests, observations).handle(status("status-full", "{\"networkMode\":\"DIRECT\"}"),
        "event.metric.status-full.sw1.swarm-controller.inst1");
    assertThat(store.find("sw1")).contains(recovered);
    assertThat(recovered.getControllerState()).isEqualTo(ControllerState.READY);
    verify(observations).handleControllerStatusFull(eq("sw1"), eq("inst1"), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void unknownDeltaRequestsFullWithoutDiscovering() {
    var store = new SwarmStore();
    var requests = mock(ControlPlaneStatusRequestPublisher.class);
    var observations = mock(SwarmOperationObservationHandler.class);
    listener(store, requests, observations).handle(status("status-delta", "{}"),
        "event.metric.status-delta.sw1.swarm-controller.inst1");
    verify(requests).requestStatusForSwarm(eq("sw1"), anyString(), anyString());
    assertThat(store.all()).isEmpty();
    verifyNoInteractions(discovery, observations);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"status-full,inst-other,run-1", "status-full,inst1,run-other",
      "status-delta,inst-other,run-1", "status-delta,inst1,run-other"})
  void conflictingIdentityReportsErrorWithoutChangingObservation(String type, String instance, String runId) {
    var store = new SwarmStore();
    var swarm = new Swarm("sw1", "inst1", "c1", "run-1", NetworkMode.DIRECT);
    store.register(swarm);
    var requests = mock(ControlPlaneStatusRequestPublisher.class);
    var observations = mock(SwarmOperationObservationHandler.class);
    String body = status(type, "status-full".equals(type) ? "{\"networkMode\":\"DIRECT\"}" : "{}")
        .replace("inst1", instance).replace("run-1", runId);
    listener(store, requests, observations).handle(body, "event.metric." + type + ".sw1.swarm-controller." + instance);
    assertThat(store.find("sw1")).contains(swarm);
    assertThat(swarm.getControllerStatusFull()).isNull();
    assertThat(swarm.getControllerStatusReceivedAt()).isNull();
    assertThat(swarm.getControllerState()).isEqualTo(ControllerState.PROVISIONING);
    var error = org.mockito.ArgumentCaptor.forClass(HiveJournal.HiveJournalEntry.class);
    verify(journal).append(error.capture());
    assertThat(error.getValue().severity()).isEqualTo("ERROR");
    assertThat(error.getValue().type()).isEqualTo("controller-identity-conflict");
    assertThat(error.getValue().data().get("reason").toString())
        .contains("expected instance=inst1 runId=run-1", "received instance=" + instance + " runId=" + runId);
    verifyNoInteractions(discovery, observations, requests);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"status-full", "status-delta"})
  void replacementBetweenIdentityCheckAndWriteRejectsOldStatus(String type) throws Exception {
    var atAdmission = new java.util.concurrent.CountDownLatch(1);
    var resume = new java.util.concurrent.CountDownLatch(1);
    var store = new SwarmStore() {
      @Override public boolean updateIfCurrent(Swarm expected, Runnable update) {
        atAdmission.countDown();
        try {
          assertThat(resume.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
          throw new AssertionError(exception);
        }
        return super.updateIfCurrent(expected, update);
      }
    };
    var old = new Swarm("sw1", "inst1", "c1", "run-1", NetworkMode.DIRECT);
    store.register(old);
    store.cacheControllerStatusFull("sw1", mapper.readTree(status("status-full", "{\"networkMode\":\"DIRECT\"}")), Instant.now());
    var requests = mock(ControlPlaneStatusRequestPublisher.class);
    var observations = mock(SwarmOperationObservationHandler.class);
    var listener = listener(store, requests, observations);
    var replacement = new Swarm("sw1", "inst-new", "c-new", "run-new", NetworkMode.DIRECT);
    try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      var received = executor.submit(() -> listener.handle(status(type,
          "status-full".equals(type) ? "{\"networkMode\":\"DIRECT\"}" : "{}"),
          "event.metric." + type + ".sw1.swarm-controller.inst1"));
      try {
        assertThat(atAdmission.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        store.clear();
        store.register(replacement);
      } finally {
        resume.countDown();
      }
      received.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
    assertThat(store.find("sw1")).contains(replacement);
    assertThat(replacement.getControllerStatusFull()).isNull();
    assertThat(replacement.getControllerStatusReceivedAt()).isNull();
    assertThat(replacement.getObservation()).isEmpty();
    verifyNoInteractions(observations);
    var error = org.mockito.ArgumentCaptor.forClass(HiveJournal.HiveJournalEntry.class);
    verify(journal).append(error.capture());
    assertThat(error.getValue().type()).isEqualTo("controller-identity-conflict");
    assertThat(error.getValue().data().get("reason").toString()).contains("inst-new", "run-new", "inst1", "run-1");
  }

  @Test
  void rejectsMissingTransportIdentityWithoutThrowing() {
    SwarmStore store = mock(SwarmStore.class);
    ControlPlaneStatusRequestPublisher requests = mock(ControlPlaneStatusRequestPublisher.class);
    SwarmOperationObservationHandler observations = mock(SwarmOperationObservationHandler.class);
    ControllerStatusListener listener = listener(store, requests, observations);

    assertThatCode(() -> listener.handle("{}", " ")).doesNotThrowAnyException();
    assertThatCode(() -> listener.handle(" ", "event.metric.status-full.sw1.swarm-controller.inst1"))
        .doesNotThrowAnyException();
    verifyNoInteractions(store, requests, observations);
  }

  private ControllerStatusListener listener(
      SwarmStore store,
      ControlPlaneStatusRequestPublisher requests,
      SwarmOperationObservationHandler observations) {
    return new ControllerStatusListener(
        io.pockethive.controlplane.codec.ControlPlaneCodec.create(),
        new ControllerStatusService(store, mapper, requests, observations, discovery, journal),
        journal);
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
