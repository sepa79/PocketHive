package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.manager.guard.BufferGuardSettings;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SwarmConfigUpdateHandlerTest {

  @Mock
  private SwarmLifecycle lifecycle;

  @Mock
  private SwarmControllerNetworkContext networkContext;

  @Mock
  private SwarmControllerStatusPublisher statusPublisher;

  @Mock
  private SwarmCommandReadiness readiness;

  @Mock
  private SwarmControllerResultPublisher results;

  private SwarmConfigUpdateHandler handler;

  @BeforeEach
  void setUp() {
    handler = new SwarmConfigUpdateHandler(
        lifecycle,
        new ObjectMapper().findAndRegisterModules(),
        "swarm-controller",
        "controller-1",
        networkContext,
        statusPublisher,
        readiness,
        results);
    lenient().when(readiness.snapshot()).thenReturn(
        new SwarmCommandReadinessSnapshot(true, true, false, WorkloadState.RUNNING));
  }

  @Test
  void appliesControllerEnabledFlagAndPublishesOneSuccess() {
    ControlSignal signal = signal("swarm-controller", "controller-1", "orchestrator-1", Map.of("enabled", false));

    handler.handle(signal, TEST_SWARM_ID);

    verify(lifecycle).setSwarmEnabled(false);
    verify(statusPublisher).publishDelta();
    verify(results).publishConfig(signal, TerminalStatus.SUCCEEDED);
  }

  @Test
  void rejectsOrdinaryConfigWhenWorkloadIsNotRunning() {
    ControlSignal signal = signal("swarm-controller", "controller-1", "orchestrator-1", Map.of("enabled", true));
    when(readiness.snapshot()).thenReturn(
        new SwarmCommandReadinessSnapshot(true, true, false, WorkloadState.STOPPED));

    handler.handle(signal, TEST_SWARM_ID);

    verify(lifecycle, never()).setSwarmEnabled(true);
    verify(results).publishConfig(signal, TerminalStatus.REJECTED);
  }

  @Test
  void appliesNetworkOnlyConfigWithoutRequiringRunningWorkload() {
    ControlSignal signal = signal(
        "swarm-controller", "controller-1", "orchestrator-1", Map.of("networkMode", "DIRECT"));
    when(readiness.snapshot()).thenReturn(
        new SwarmCommandReadinessSnapshot(true, true, false, WorkloadState.STOPPED));
    when(networkContext.isOnlyNetworkContext(eq("swarm-controller"), any(JsonNode.class)))
        .thenReturn(true);
    when(networkContext.applyOverride(any(JsonNode.class))).thenReturn(true);

    handler.handle(signal, TEST_SWARM_ID);

    verify(networkContext).applyOverride(any(JsonNode.class));
    verify(statusPublisher).publishDelta();
    verify(results).publishConfig(signal, TerminalStatus.SUCCEEDED);
  }

  @Test
  void ignoresSelfIssuedAllScopeBroadcast() {
    ControlSignal signal = signal(
        ControlScope.ALL, ControlScope.ALL, "controller-1", Map.of("enabled", true));

    handler.handle(signal, TEST_SWARM_ID);

    verifyNoInteractions(lifecycle, networkContext, statusPublisher, readiness, results);
  }

  @Test
  void appliesScenarioOverridesAndPublishesFreshStatus() {
    ControlSignal signal = signal(
        "swarm-controller",
        "controller-1",
        "orchestrator-1",
        Map.of("scenario", Map.of("runs", 4, "reset", true)));

    handler.handle(signal, TEST_SWARM_ID);

    verify(lifecycle).setScenarioRuns(4);
    verify(lifecycle).resetScenarioPlan();
    verify(statusPublisher).publishDelta();
    verify(results).publishConfig(signal, TerminalStatus.SUCCEEDED);
  }

  @Test
  void disablesExistingBufferGuardThroughLifecycleOwner() {
    ControlSignal signal = signal(
        "swarm-controller",
        "controller-1",
        "orchestrator-1",
        Map.of("trafficPolicy", Map.of("bufferGuard", Map.of("enabled", false))));
    when(lifecycle.bufferGuards()).thenReturn(List.of(settings()));

    handler.handle(signal, TEST_SWARM_ID);

    verify(lifecycle).configureBufferGuards(List.of());
    verify(results).publishConfig(signal, TerminalStatus.SUCCEEDED);
  }

  @Test
  void rejectsBufferGuardOverrideWhenScenarioHasNoGuard() {
    ControlSignal signal = signal(
        "swarm-controller",
        "controller-1",
        "orchestrator-1",
        Map.of("trafficPolicy", Map.of("bufferGuard", Map.of("targetDepth", 220))));
    when(lifecycle.bufferGuards()).thenReturn(List.of());

    handler.handle(signal, TEST_SWARM_ID);

    verify(results).publishFailure(
        eq(signal),
        eq(ControlPlaneSignals.CONFIG_UPDATE),
        argThat(failure -> failure instanceof IllegalStateException
            && failure.getMessage().contains("no guard is configured")));
    verify(results, never()).publishConfig(signal, TerminalStatus.SUCCEEDED);
  }

  @Test
  void publishesCanonicalFailureWhenApplicationThrows() {
    ControlSignal signal = signal(
        "swarm-controller", "controller-1", "orchestrator-1", Map.of("enabled", true));
    IllegalStateException failure = new IllegalStateException("network update failed");
    when(networkContext.applyOverride(any(JsonNode.class))).thenThrow(failure);

    handler.handle(signal, TEST_SWARM_ID);

    verify(results).publishFailure(signal, ControlPlaneSignals.CONFIG_UPDATE, failure);
    verify(results, never()).publishConfig(signal, TerminalStatus.SUCCEEDED);
  }

  @Test
  void successPublicationFailureIsNotTranslatedToSecondTerminalResult() {
    ControlSignal signal = signal(
        "swarm-controller", "controller-1", "orchestrator-1", Map.of("enabled", false));
    IllegalStateException failure = new IllegalStateException("journal unavailable");
    doThrow(failure).when(results).publishConfig(signal, TerminalStatus.SUCCEEDED);

    assertThatThrownBy(() -> handler.handle(signal, TEST_SWARM_ID)).isSameAs(failure);

    verify(results, never()).publishFailure(
        eq(signal), eq(ControlPlaneSignals.CONFIG_UPDATE), any(Exception.class));
  }

  private static ControlSignal signal(
      String role,
      String instance,
      String origin,
      Map<String, Object> data) {
    return ControlSignal.forInstance(
        ControlPlaneSignals.CONFIG_UPDATE,
        TEST_SWARM_ID,
        role,
        instance,
        origin,
        "correlation-1",
        "idempotency-1",
        data);
  }

  private static BufferGuardSettings settings() {
    return new BufferGuardSettings(
        "generator-output",
        "ph-test.generator-output",
        "generator",
        20,
        200,
        150,
        260,
        Duration.ofSeconds(5),
        3,
        new BufferGuardSettings.Adjustment(10, 20, 1, 100),
        new BufferGuardSettings.Prefill(true, Duration.ofSeconds(2), 30),
        new BufferGuardSettings.Backpressure(
            "moderator-input", "ph-test.moderator-input", 300, 180, 40));
  }
}
