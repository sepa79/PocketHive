package io.pockethive.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.swarm.model.lifecycle.Target;
import io.pockethive.swarm.model.lifecycle.TerminalResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandTerminalEnvelopeTest {

  private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");
  private static final ControlScope CONTROLLER =
      new ControlScope("alpha", "swarm-controller", "alpha-controller-1");
  private static final ControlScope ORCHESTRATOR =
      new ControlScope("alpha", "orchestrator", "orchestrator-1");
  private static final Map<String, Object> RUNTIME =
      Map.of("templateId", "template-1", "runId", "run-1");
  private static final TerminalResult RESULT = new TerminalResult(
      TerminalStatus.SUCCEEDED,
      false,
      Map.of("target", new Target("swarm-controller", "alpha-controller-1")));

  @Test
  void executorResultHasDedicatedKindAndRequiresBothIdentities() {
    CommandResult result = new CommandResult(
        NOW,
        ControlPlaneEnvelopeVersion.CURRENT,
        "result",
        "swarm-start",
        "swarm-controller:alpha-controller-1",
        CONTROLLER,
        "correlation-1",
        "idempotency-1",
        RUNTIME,
        RESULT);

    assertEquals("result", result.kind());
    assertSame(RESULT, result.data());

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new CommandResult(
        NOW, ControlPlaneEnvelopeVersion.CURRENT, "result", "swarm-start", "controller",
        CONTROLLER, "correlation-1", " ", null, RESULT));
    assertTrue(error.getMessage().contains("idempotencyKey"));
  }

  @Test
  void onlyOrchestratorCanOwnPublicOutcomeScope() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new CommandOutcome(
        NOW,
        ControlPlaneEnvelopeVersion.CURRENT,
        "outcome",
        "swarm-start",
        "swarm-controller:alpha-controller-1",
        CONTROLLER,
        "correlation-1",
        "idempotency-1",
        RUNTIME,
        RESULT));
    assertTrue(error.getMessage().contains("orchestrator"));

    CommandOutcome outcome = new CommandOutcome(
        NOW,
        ControlPlaneEnvelopeVersion.CURRENT,
        "outcome",
        "swarm-start",
        "orchestrator-1",
        ORCHESTRATOR,
        "correlation-1",
        "idempotency-1",
        RUNTIME,
        RESULT);
    assertEquals(ORCHESTRATOR, outcome.scope());
  }

  @Test
  void terminalStatusUsesTheSchemaWireValue() throws Exception {
    CommandOutcome outcome = new CommandOutcome(
        NOW,
        ControlPlaneEnvelopeVersion.CURRENT,
        "outcome",
        "swarm-start",
        "orchestrator-1",
        ORCHESTRATOR,
        "correlation-1",
        "idempotency-1",
        RUNTIME,
        RESULT);

    String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(outcome);

    assertTrue(json.contains("\"version\":\"2\""));
    assertTrue(json.contains("\"status\":\"Succeeded\""));
  }

  @Test
  void swarmScopedTerminalEventsRequireRuntime() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new CommandResult(
        NOW, ControlPlaneEnvelopeVersion.CURRENT, "result", "swarm-start", "controller",
        CONTROLLER, "correlation-1", "idempotency-1", null, RESULT));

    assertTrue(error.getMessage().contains("runtime is required"));
  }
}
