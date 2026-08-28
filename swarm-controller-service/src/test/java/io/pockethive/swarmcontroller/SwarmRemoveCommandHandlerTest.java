package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.filesystem.FilesystemSwarmRemoveStore;
import io.pockethive.swarm.model.lifecycle.RemoveRequest;
import io.pockethive.swarm.model.lifecycle.RemoveResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SwarmRemoveCommandHandlerTest {

  private static final String INSTANCE_ID = "controller-1";
  private static final String CORRELATION_ID = "correlation-1";
  private static final String IDEMPOTENCY_KEY = "idempotency-1";

  @Mock
  private SwarmLifecycle lifecycle;

  @Mock
  private FilesystemSwarmRemoveStore removeStore;

  private SwarmRemoveCommandHandler handler;

  @BeforeEach
  void setUp() {
    handler = new SwarmRemoveCommandHandler(
        lifecycle, removeStore, SwarmControllerTestProperties.defaults(), INSTANCE_ID);
  }

  @Test
  void persistsSuccessfulLifecycleRemoval() {
    RemoveRequest request = request(INSTANCE_ID, CORRELATION_ID, IDEMPOTENCY_KEY);
    when(removeStore.findResult(TEST_SWARM_ID, CORRELATION_ID)).thenReturn(Optional.empty());
    when(removeStore.loadRequest(TEST_SWARM_ID, CORRELATION_ID)).thenReturn(request);
    when(lifecycle.remove()).thenReturn(List.of());

    handler.handle(signal(CORRELATION_ID, IDEMPOTENCY_KEY));

    verify(lifecycle).remove();
    ArgumentCaptor<RemoveResult> saved = ArgumentCaptor.forClass(RemoveResult.class);
    verify(removeStore).saveResult(saved.capture());
    assertThat(saved.getValue().status()).isEqualTo(TerminalStatus.SUCCEEDED);
    assertThat(saved.getValue().swarmId()).isEqualTo(TEST_SWARM_ID);
    assertThat(saved.getValue().controllerInstance()).isEqualTo(INSTANCE_ID);
    assertThat(saved.getValue().correlationId()).isEqualTo(CORRELATION_ID);
    assertThat(saved.getValue().idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
  }

  @Test
  void persistsRetryableFailureFromLifecycleRemoval() {
    RemoveRequest request = request(INSTANCE_ID, CORRELATION_ID, IDEMPOTENCY_KEY);
    when(removeStore.findResult(TEST_SWARM_ID, CORRELATION_ID)).thenReturn(Optional.empty());
    when(removeStore.loadRequest(TEST_SWARM_ID, CORRELATION_ID)).thenReturn(request);
    doThrow(new RuntimeException("boom")).when(lifecycle).remove();

    handler.handle(signal(CORRELATION_ID, IDEMPOTENCY_KEY));

    ArgumentCaptor<RemoveResult> saved = ArgumentCaptor.forClass(RemoveResult.class);
    verify(removeStore).saveResult(saved.capture());
    assertThat(saved.getValue().status()).isEqualTo(TerminalStatus.FAILED);
    assertThat(saved.getValue().retryable()).isTrue();
    assertThat(saved.getValue().errors()).singleElement().satisfies(error -> {
      assertThat(error.code()).isEqualTo("RuntimeException");
      assertThat(error.message()).isEqualTo("boom");
    });
  }

  @Test
  void reusesExistingResultForTheSameOperationIdentity() {
    RemoveResult existing = RemoveResult.succeeded(
        TEST_SWARM_ID, "run-1", INSTANCE_ID, CORRELATION_ID, IDEMPOTENCY_KEY, List.of(), Instant.now());
    when(removeStore.findResult(TEST_SWARM_ID, CORRELATION_ID)).thenReturn(Optional.of(existing));

    handler.handle(signal(CORRELATION_ID, IDEMPOTENCY_KEY));

    verify(removeStore, never()).loadRequest(TEST_SWARM_ID, CORRELATION_ID);
    verify(lifecycle, never()).remove();
    verify(removeStore, never()).saveResult(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsExistingResultWithDifferentIdempotencyKey() {
    RemoveResult existing = RemoveResult.succeeded(
        TEST_SWARM_ID, "run-1", INSTANCE_ID, CORRELATION_ID, "different", List.of(), Instant.now());
    when(removeStore.findResult(TEST_SWARM_ID, CORRELATION_ID)).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> handler.handle(signal(CORRELATION_ID, IDEMPOTENCY_KEY)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Existing remove result belongs to a different idempotency key");

    verify(lifecycle, never()).remove();
  }

  @Test
  void rejectsRequestForDifferentControllerInstance() {
    when(removeStore.findResult(TEST_SWARM_ID, CORRELATION_ID)).thenReturn(Optional.empty());
    when(removeStore.loadRequest(TEST_SWARM_ID, CORRELATION_ID))
        .thenReturn(request("different-controller", CORRELATION_ID, IDEMPOTENCY_KEY));

    assertThatThrownBy(() -> handler.handle(signal(CORRELATION_ID, IDEMPOTENCY_KEY)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Remove signal does not match filesystem request identity");

    verify(lifecycle, never()).remove();
    verify(removeStore, never()).saveResult(org.mockito.ArgumentMatchers.any());
  }

  private static ControlSignal signal(String correlationId, String idempotencyKey) {
    return ControlSignal.forInstance(
        ControlPlaneSignals.SWARM_REMOVE,
        TEST_SWARM_ID,
        "swarm-controller",
        INSTANCE_ID,
        "orchestrator-1",
        correlationId,
        idempotencyKey,
        null);
  }

  private static RemoveRequest request(
      String controllerInstance, String correlationId, String idempotencyKey) {
    return RemoveRequest.create(
        TEST_SWARM_ID, "run-1", controllerInstance, correlationId, idempotencyKey, Instant.now());
  }
}
