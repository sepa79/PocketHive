package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.swarm.model.BufferGuardPolicy;
import io.pockethive.swarm.model.NetworkMode;
import io.pockethive.swarm.model.TrafficPolicy;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SwarmControllerStatusPublisherTest {

  private static final String INSTANCE = "controller-1";
  private static final String STARTUP_SHA256 = "a".repeat(64);
  private static final Map<String, Object> RUNTIME =
      Map.of("templateId", "template-1", "runId", "run-1");

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final SwarmLifecycle lifecycle = mock(SwarmLifecycle.class);
  private final SwarmWorkerStatusHandler workerStatuses = mock(SwarmWorkerStatusHandler.class);
  private final SwarmHealthJournal healthJournal = mock(SwarmHealthJournal.class);
  private final ControlPlanePublisher controlPlane = mock(ControlPlanePublisher.class);
  private final AtomicBoolean initialized = new AtomicBoolean(true);

  @BeforeEach
  void setUp() {
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(1, 1, 1, 1, Instant.now()));
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.RUNNING);
    when(lifecycle.isReadyForWork()).thenReturn(true);
    when(lifecycle.scenarioProgress()).thenReturn(Map.of());
    when(lifecycle.expectedWorkers()).thenReturn(List.of());
    when(lifecycle.workBindingsSnapshot()).thenReturn(Map.of());
    when(lifecycle.bufferGuards()).thenReturn(List.of());
    when(workerStatuses.workersSnapshot()).thenReturn(List.of());
    when(workerStatuses.diagnosticsSnapshot()).thenReturn(Map.of());
  }

  @Test
  void fullStatusPublishesWorkerIoStateAndCanonicalControllerRoutes() {
    when(workerStatuses.workersSnapshot()).thenReturn(List.of(Map.of(
        "role", "generator",
        "instance", "generator-1",
        "enabled", true,
        "tps", 4,
        "lastSeenAt", "2026-09-01T09:00:00Z",
        "stale", false,
        "ioState", Map.of("work", Map.of("input", "ok", "output", "blocked")))));

    publisher().publishFull();

    EventMessage event = publishedEvent();
    JsonNode payload = mapper.valueToTree(event.payload());
    JsonNode context = payload.path("data").path("context");
    assertThat(event.routingKey()).isEqualTo(
        "event.metric.status-full." + TEST_SWARM_ID + ".swarm-controller." + INSTANCE);
    assertThat(context.path("workers").get(0).path("ioState").path("work").path("output").asText())
        .isEqualTo("blocked");
    assertThat(context.path("controllerState").asText()).isEqualTo("READY");
    JsonNode routes = payload.path("data").path("io").path("control").path("queues").path("routes");
    assertThat(routes.isArray()).isTrue();
    List<String> advertisedRoutes = new ArrayList<>();
    routes.forEach(route -> advertisedRoutes.add(route.asText()));
    assertThat(advertisedRoutes).containsExactly(
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, "ALL", "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "swarm-controller", INSTANCE),
        ControlPlaneRouting.signal(ControlPlaneSignals.CONFIG_UPDATE, TEST_SWARM_ID, "ALL", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, "ALL", "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, TEST_SWARM_ID, "swarm-controller", "ALL"),
        ControlPlaneRouting.signal(ControlPlaneSignals.STATUS_REQUEST, TEST_SWARM_ID, "swarm-controller", INSTANCE),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_START, TEST_SWARM_ID, "swarm-controller", INSTANCE),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_STOP, TEST_SWARM_ID, "swarm-controller", INSTANCE),
        ControlPlaneRouting.signal(ControlPlaneSignals.SWARM_REMOVE, TEST_SWARM_ID, "swarm-controller", INSTANCE));
    verify(lifecycle).snapshotQueueStats();
    verify(healthJournal).observe(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void deltaDerivesDegradedHealthWithoutPublishingAFullProjection() {
    when(lifecycle.getMetrics()).thenReturn(new SwarmMetrics(3, 2, 2, 2, Instant.now()));

    publisher().publishDelta();

    EventMessage event = publishedEvent();
    JsonNode context = mapper.valueToTree(event.payload()).path("data").path("context");
    assertThat(event.payload().type()).isEqualTo(StatusMetric.STATUS_DELTA);
    assertThat(context.path("health").asText()).isEqualTo("DEGRADED");
    assertThat(context.has("workers")).isFalse();
    verify(lifecycle, never()).snapshotQueueStats();
  }

  @Test
  void unknownWorkloadPublishesFailedControllerHealth() {
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.UNKNOWN);

    publisher().publishDelta();

    JsonNode context = mapper.valueToTree(publishedEvent().payload()).path("data").path("context");
    assertThat(context.path("health").asText()).isEqualTo("FAILED");
    assertThat(context.path("controllerState").asText()).isEqualTo("FAILED");
  }

  @Test
  void fullStatusIncludesTheEffectiveTrafficPolicy() {
    TrafficPolicy policy = new TrafficPolicy(new BufferGuardPolicy(
        true, "gen-out", 200, 150, 260, "5s", 3, null, null, null));
    when(lifecycle.trafficPolicy()).thenReturn(policy);

    publisher().publishFull();

    JsonNode bufferGuard = mapper.valueToTree(publishedEvent().payload())
        .path("data").path("config").path("trafficPolicy").path("bufferGuard");
    assertThat(bufferGuard.path("queueAlias").asText()).isEqualTo("gen-out");
    assertThat(bufferGuard.path("targetDepth").asInt()).isEqualTo(200);
  }

  @Test
  void fullStatusStillPublishesWhenQueueMetricsRefreshFails() {
    doThrow(new IllegalStateException("queue stats unavailable"))
        .when(lifecycle).snapshotQueueStats();

    publisher().publishFull();

    assertThat(publishedEvent().payload().type()).isEqualTo(StatusMetric.STATUS_FULL);
  }

  @Test
  void networkOverrideChangesOnlyTheStatusProjection() throws Exception {
    SwarmControllerNetworkContext networkContext =
        new SwarmControllerNetworkContext(
            lifecycle, "swarm-controller", null, NetworkMode.PROXIED, "profile-1");
    SwarmControllerStatusPublisher publisher = publisher(networkContext);
    JsonNode override = mapper.readTree("""
        {"networkMode":"DIRECT","networkProfileId":"ignored-after-direct"}
        """);

    assertThat(networkContext.applyOverride(override)).isTrue();
    publisher.publishFull();

    JsonNode context = mapper.valueToTree(publishedEvent().payload()).path("data").path("context");
    assertThat(context.path("networkMode").asText()).isEqualTo("DIRECT");
    assertThat(context.has("networkProfileId")).isFalse();
  }

  private SwarmControllerStatusPublisher publisher() {
    return publisher(new SwarmControllerNetworkContext(
        lifecycle, "swarm-controller", null, NetworkMode.DIRECT, null));
  }

  private SwarmControllerStatusPublisher publisher(SwarmControllerNetworkContext networkContext) {
    return new SwarmControllerStatusPublisher(
        lifecycle,
        workerStatuses,
        healthJournal,
        SwarmControllerTestProperties.defaults(),
        INSTANCE,
        controlPlane,
        RUNTIME,
        STARTUP_SHA256,
        initialized::get,
        Instant.parse("2026-09-01T09:00:00Z"),
        networkContext);
  }

  private EventMessage publishedEvent() {
    ArgumentCaptor<EventMessage> event = ArgumentCaptor.forClass(EventMessage.class);
    verify(controlPlane).publishEvent(event.capture());
    return event.getValue();
  }
}
