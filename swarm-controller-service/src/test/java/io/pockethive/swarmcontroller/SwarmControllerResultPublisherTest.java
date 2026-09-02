package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.CanonicalPayloadDigest;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.messaging.ControlPlaneEmitter;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import io.pockethive.swarm.model.lifecycle.WorkloadState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SwarmControllerResultPublisherTest {

  private static final Instant NOW = Instant.parse("2026-09-01T12:00:00Z");

  @Mock
  private SwarmLifecycle lifecycle;

  @Mock
  private ControlPlaneEmitter emitter;

  private ObjectMapper mapper;
  private SwarmControllerResultPublisher publisher;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper().findAndRegisterModules();
    publisher = new SwarmControllerResultPublisher(
        lifecycle,
        mapper,
        emitter,
        "swarm-controller",
        "controller-1",
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void lifecycleFailurePublishesCanonicalStateAndExactNonConvergence() {
    ControlSignal signal = signal(ControlPlaneSignals.SWARM_START, Map.of());
    Target worker = new Target("generator", "generator-1");
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.STARTING);

    publisher.publishLifecycle(
        signal, ControlPlaneSignals.SWARM_START, TerminalStatus.FAILED, List.of(worker));

    ArgumentCaptor<ControlPlaneEmitter.ResultContext> context =
        ArgumentCaptor.forClass(ControlPlaneEmitter.ResultContext.class);
    verify(emitter).emitResult(context.capture());
    TerminalResult result = context.getValue().result();
    assertThat(context.getValue().signal()).isEqualTo(ControlPlaneSignals.SWARM_START);
    assertThat(context.getValue().timestamp()).isEqualTo(NOW);
    assertThat(result.status()).isEqualTo(TerminalStatus.FAILED);
    assertThat(result.retryable()).isTrue();
    assertThat(result.context())
        .containsEntry("target", new Target("swarm-controller", "controller-1"))
        .containsEntry("requestedWorkloadState", "RUNNING")
        .containsEntry("observedWorkloadState", "STARTING")
        .containsEntry("nonConvergedWorkers", List.of(worker));
  }

  @Test
  void configSuccessPublishesRequestedAndObservedStateWithCanonicalDigest() {
    Map<String, Object> data = Map.of("enabled", false, "rate", 7);
    ControlSignal signal = signal(ControlPlaneSignals.CONFIG_UPDATE, data);
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.STOPPED);

    publisher.publishConfig(signal, TerminalStatus.SUCCEEDED);

    ArgumentCaptor<ControlPlaneEmitter.ResultContext> context =
        ArgumentCaptor.forClass(ControlPlaneEmitter.ResultContext.class);
    verify(emitter).emitResult(context.capture());
    assertThat(context.getValue().result().context())
        .containsEntry("requestedEnabled", false)
        .containsEntry("observedEnabled", false)
        .containsEntry("appliedConfigSha256", CanonicalPayloadDigest.sha256(mapper, data));
  }

  @Test
  void failurePreservesOperationAndExceptionEvidence() {
    ControlSignal signal = signal(ControlPlaneSignals.SWARM_STOP, Map.of());
    when(lifecycle.getWorkloadState()).thenReturn(WorkloadState.STOPPING);

    publisher.publishFailure(
        signal, ControlPlaneSignals.SWARM_STOP, new IllegalStateException("stop failed"));

    ArgumentCaptor<ControlPlaneEmitter.FailureContext> context =
        ArgumentCaptor.forClass(ControlPlaneEmitter.FailureContext.class);
    verify(emitter).emitFailure(context.capture());
    assertThat(context.getValue().signal()).isEqualTo(ControlPlaneSignals.SWARM_STOP);
    assertThat(context.getValue().phase()).isEqualTo("stop");
    assertThat(context.getValue().code()).isEqualTo("IllegalStateException");
    assertThat(context.getValue().message()).isEqualTo("stop failed");
    assertThat(context.getValue().result().status()).isEqualTo(TerminalStatus.FAILED);
    assertThat(context.getValue().timestamp()).isEqualTo(NOW);
  }

  private static ControlSignal signal(String operation, Map<String, Object> data) {
    return ControlSignal.forInstance(
        operation,
        TEST_SWARM_ID,
        "swarm-controller",
        "controller-1",
        "orchestrator-1",
        "correlation-1",
        "idempotency-1",
        data);
  }
}
