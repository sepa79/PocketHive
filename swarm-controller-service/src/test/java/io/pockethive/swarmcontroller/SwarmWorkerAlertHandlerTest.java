package io.pockethive.swarmcontroller;

import static io.pockethive.swarmcontroller.SwarmControllerTestProperties.TEST_SWARM_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.control.AlertMessage;
import io.pockethive.control.ConfirmationScope;
import io.pockethive.control.ControlScope;
import io.pockethive.controlplane.ControlPlaneSignals;
import io.pockethive.controlplane.routing.ControlPlaneRouting;
import io.pockethive.swarmcontroller.runtime.SwarmJournal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SwarmWorkerAlertHandlerTest {

  private static final String CONTROLLER_INSTANCE = "controller-1";
  private static final String WORKER_ROLE = "generator";
  private static final String WORKER_INSTANCE = "generator-1";

  @Mock
  private SwarmLifecycle lifecycle;

  @Mock
  private SwarmJournal journal;

  private SwarmWorkerAlertHandler handler;

  @BeforeEach
  void setUp() {
    handler = new SwarmWorkerAlertHandler(
        lifecycle, journal, new ObjectMapper().findAndRegisterModules(), CONTROLLER_INSTANCE);
  }

  @Test
  void appliesConfigErrorEvidenceAndReturnsItsFailureReason() {
    AlertMessage alert = alert("worker-1", Map.of("phase", ControlPlaneSignals.CONFIG_UPDATE));
    when(lifecycle.handleConfigUpdateError(WORKER_ROLE, WORKER_INSTANCE, "bad config"))
        .thenReturn(Optional.of("bad config"));

    Optional<String> failure = handler.handle(routingKey(), alert);

    assertThat(failure).contains("bad config");
    verify(lifecycle).handleConfigUpdateError(WORKER_ROLE, WORKER_INSTANCE, "bad config");
    ArgumentCaptor<SwarmJournal.SwarmJournalEntry> journalEntry =
        ArgumentCaptor.forClass(SwarmJournal.SwarmJournalEntry.class);
    verify(journal).append(journalEntry.capture());
    assertThat(journalEntry.getValue().type()).isEqualTo(AlertMessage.TYPE);
    assertThat(journalEntry.getValue().scope().instance()).isEqualTo(WORKER_INSTANCE);
  }

  @Test
  void journalsNonConfigAlertsWithoutChangingLifecycle() {
    AlertMessage alert = alert("worker-1", Map.of("phase", "runtime"));

    Optional<String> failure = handler.handle(routingKey(), alert);

    assertThat(failure).isEmpty();
    verify(journal).append(org.mockito.ArgumentMatchers.any());
    verify(lifecycle, never()).handleConfigUpdateError(
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
  }

  @Test
  void ignoresControllerOriginAlreadyJournaledOnOutput() {
    AlertMessage alert = alert(CONTROLLER_INSTANCE, Map.of("phase", ControlPlaneSignals.CONFIG_UPDATE));

    Optional<String> failure = handler.handle(routingKey(), alert);

    assertThat(failure).isEmpty();
    verifyNoInteractions(journal, lifecycle);
  }

  private static AlertMessage alert(String origin, Map<String, Object> context) {
    return new AlertMessage(
        Instant.now(),
        "2",
        AlertMessage.KIND,
        AlertMessage.TYPE,
        origin,
        ControlScope.forInstance(TEST_SWARM_ID, WORKER_ROLE, WORKER_INSTANCE),
        "correlation-1",
        "idempotency-1",
        Map.of("templateId", "template-1", "runId", "run-1"),
        new AlertMessage.AlertData(
            "error", "ValidationError", "bad config", null, null, null, context));
  }

  private static String routingKey() {
    return ControlPlaneRouting.event(
        AlertMessage.KIND,
        AlertMessage.TYPE,
        new ConfirmationScope(TEST_SWARM_ID, WORKER_ROLE, WORKER_INSTANCE));
  }
}
