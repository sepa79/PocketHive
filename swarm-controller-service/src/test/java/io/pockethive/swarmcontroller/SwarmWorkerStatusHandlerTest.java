package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.ControlScope;
import io.pockethive.control.StatusMetric;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SwarmWorkerStatusHandlerTest {

  private static final String ROLE = "generator";
  private static final String INSTANCE = "generator-1";

  @Mock
  private SwarmLifecycle lifecycle;

  @Mock
  private SwarmWorkerErrorJournal workerErrors;

  private SwarmWorkerStatusHandler handler;

  @BeforeEach
  void setUp() {
    handler = new SwarmWorkerStatusHandler(lifecycle, new ObjectMapper(), workerErrors);
  }

  @Test
  void appliesAFullDisabledObservationAndUpdatesReadOnlyProjections() {
    when(lifecycle.markReady(ROLE, INSTANCE)).thenReturn(true);
    StatusMetric status = status(
        StatusMetric.STATUS_FULL,
        Map.of(
            "enabled", false,
            "tps", 7,
            "config", Map.of("mode", "test"),
            "context", Map.of("diagnostics", Map.of("lag", 3)),
            "ioState", Map.of("work", Map.of("input", "ok", "output", "blocked"))));

    boolean startupReadyTransition = handler.observe(ROLE, INSTANCE, status, true);

    assertThat(startupReadyTransition).isTrue();
    InOrder lifecycleOrder = inOrder(lifecycle);
    lifecycleOrder.verify(lifecycle).updateHeartbeat(ROLE, INSTANCE);
    lifecycleOrder.verify(lifecycle).recordStatusSnapshot(ROLE, INSTANCE, false);
    lifecycleOrder.verify(lifecycle).markReady(ROLE, INSTANCE);
    verify(workerErrors).observe(
        org.mockito.ArgumentMatchers.eq(ROLE),
        org.mockito.ArgumentMatchers.eq(INSTANCE),
        org.mockito.ArgumentMatchers.any());
    assertThat(handler.workersSnapshot()).singleElement().satisfies(worker -> {
      assertThat(worker).containsEntry("role", ROLE).containsEntry("instance", INSTANCE);
      assertThat(worker).containsEntry("enabled", false).containsEntry("tps", 7L);
    });
    assertThat(handler.diagnosticsSnapshot()).containsEntry(ROLE, Map.of("lag", 3));
    assertThat(handler.workersSnapshot().getFirst().get("ioState"))
        .isEqualTo(Map.of("work", Map.of("input", "ok", "output", "blocked")));
  }

  @Test
  void appliesAnEnabledDeltaWithoutRecordingAFullSnapshotOrMarkingReady() {
    boolean startupReadyTransition = handler.observe(
        ROLE,
        INSTANCE,
        status(StatusMetric.STATUS_DELTA, Map.of("enabled", true, "tps", 1)),
        false);

    assertThat(startupReadyTransition).isFalse();
    verify(lifecycle).updateHeartbeat(ROLE, INSTANCE);
    verify(lifecycle).updateEnabled(ROLE, INSTANCE, true);
    verify(lifecycle, never()).recordStatusSnapshot(
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyBoolean());
    verify(lifecycle, never()).markReady(
        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void rejectsAStatusWithoutTheRequiredEnabledFlag() {
    StatusMetric status = status(StatusMetric.STATUS_FULL, Map.of("tps", 1));

    assertThatThrownBy(() -> handler.observe(ROLE, INSTANCE, status, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("worker status data.enabled must be a boolean");

    verify(lifecycle).updateHeartbeat(ROLE, INSTANCE);
    verify(lifecycle, never()).updateEnabled(
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyBoolean());
    verify(lifecycle, never()).recordStatusSnapshot(
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.anyBoolean());
  }

  private static StatusMetric status(String type, Map<String, Object> data) {
    return new StatusMetric(
        Instant.parse("2026-08-28T12:00:00Z"),
        "2",
        StatusMetric.KIND,
        type,
        INSTANCE,
        ControlScope.forInstance(TEST_SWARM_ID, ROLE, INSTANCE),
        null,
        null,
        Map.of("templateId", "template-1", "runId", "run-1"),
        data);
  }
}
