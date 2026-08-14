package io.pockethive.swarm.model.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoveContractTest {

  private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");

  @Test
  void requestUsesTheOneVersionedFilesystemContract() {
    RemoveRequest request = RemoveRequest.create(
        "alpha", "run-1", "alpha-controller-1", "correlation-1", "idempotency-1", NOW);

    assertEquals(RemoveRequest.SCHEMA, request.schema());
    assertEquals("correlation-1", request.correlationId());
    assertEquals("idempotency-1", request.idempotencyKey());
  }

  @Test
  void successfulActionResultCannotContainErrors() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new RemoveResult(
        RemoveResult.SCHEMA,
        "alpha",
        "run-1",
        "alpha-controller-1",
        "correlation-1",
        "idempotency-1",
        TerminalStatus.SUCCEEDED,
        false,
        List.of(),
        List.of(new RemoveError("unexpected", "must fail", null)),
        NOW));
    assertTrue(error.getMessage().contains("errors"));
  }

  @Test
  void failedResultRequiresExplicitErrorEvidence() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> new RemoveResult(
        RemoveResult.SCHEMA,
        "alpha",
        "run-1",
        "alpha-controller-1",
        "correlation-1",
        "idempotency-1",
        TerminalStatus.FAILED,
        true,
        List.of(),
        List.of(),
        NOW));
    assertTrue(error.getMessage().contains("errors"));
  }

  @Test
  void resultCopiesEvidenceCollections() {
    List<RemoveResource> targets = new ArrayList<>();
    targets.add(new RemoveResource(RemoveResourceType.WORKER_RUNTIME, "worker-1"));

    RemoveResult result = RemoveResult.succeeded(
        "alpha", "run-1", "alpha-controller-1", "correlation-1", "idempotency-1", targets, NOW);
    targets.clear();

    assertEquals(1, result.targetResources().size());
    assertThrows(UnsupportedOperationException.class, () -> result.targetResources().clear());
  }
}
