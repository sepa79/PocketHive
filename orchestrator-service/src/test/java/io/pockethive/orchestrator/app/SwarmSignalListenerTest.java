package io.pockethive.orchestrator.app;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.pockethive.control.CommandResult;
import io.pockethive.control.ControlScope;
import io.pockethive.control.JournalEvent;
import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.controlplane.ControlPlaneRoles;
import io.pockethive.controlplane.codec.ControlPlaneCodec;
import io.pockethive.controlplane.routing.ControlPlaneRouting.RoutingKey;
import io.pockethive.orchestrator.domain.HiveJournal;
import io.pockethive.orchestrator.runtime.RuntimeLogSnapshotJournalService;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SwarmSignalListenerTest {

  private static final String SWARM_ID = "swarm-test";
  private static final String CONTROLLER = "controller-1";

  private final ControlPlaneCodec codec = ControlPlaneCodec.create();
  private final HiveJournal journal = mock(HiveJournal.class);
  private final SwarmOperationTerminalHandler terminalOperations = mock(SwarmOperationTerminalHandler.class);
  private SwarmSignalListener listener;

  @BeforeEach
  void setUp() {
    listener = new SwarmSignalListener(
        codec,
        journal,
        mock(RuntimeLogSnapshotJournalService.class),
        terminalOperations,
        new ControlPlaneIdentity("ALL", ControlPlaneRoles.ORCHESTRATOR, "orchestrator-1"));
  }

  @Test
  void executorResultIsDecodedAndDispatchedWithRoutingContext() throws Exception {
    CommandResult result = result(CONTROLLER);
    String routingKey = route(CONTROLLER);

    listener.handle(codec.encode(result, routingKey), routingKey);

    verify(terminalOperations).accept(
        eq(new RoutingKey("event", "result.swarm-start", SWARM_ID, ControlPlaneRoles.SWARM_CONTROLLER, CONTROLLER)),
        eq(routingKey),
        argThat(decoded -> decoded.correlationId().equals(result.correlationId())
            && decoded.idempotencyKey().equals(result.idempotencyKey())
            && decoded.data().status() == result.data().status()));
  }

  @Test
  void journalEvidenceIsPersisted() throws Exception {
    JournalEvent event = new JournalEvent(
        Instant.now(), "2", "journal", "work-journal", CONTROLLER,
        new ControlScope(SWARM_ID, "generator", "generator-1"),
        "journal-corr", "journal-idem",
        Map.of("templateId", "template-1", "runId", "run-1"),
        Map.of("status", "recorded"));
    String routingKey = "event.journal.work-journal.swarm-test.generator.generator-1";

    listener.handle(codec.encode(event, routingKey), routingKey);

    verify(journal).append(any(HiveJournal.HiveJournalEntry.class));
  }

  @Test
  void malformedTransportInputIsDropped() {
    assertThatCode(() -> listener.handle("{}", " ")).doesNotThrowAnyException();
    verify(journal).append(any(HiveJournal.HiveJournalEntry.class));
  }

  private static CommandResult result(String controller) {
    return new CommandResult(
        Instant.now(), "2", "result", "swarm-start", controller,
        new ControlScope(SWARM_ID, ControlPlaneRoles.SWARM_CONTROLLER, controller),
        "corr-1", "idem-1",
        Map.of("templateId", "template-1", "runId", "run-1"),
        new TerminalResult(TerminalStatus.SUCCEEDED, false, Map.of(
            "target", new Target(ControlPlaneRoles.SWARM_CONTROLLER, controller),
            "requestedWorkloadState", "RUNNING",
            "observedWorkloadState", "RUNNING",
            "nonConvergedWorkers", List.of())));
  }

  private static String route(String controller) {
    return "event.result.swarm-start." + SWARM_ID + "." + ControlPlaneRoles.SWARM_CONTROLLER + "." + controller;
  }
}
