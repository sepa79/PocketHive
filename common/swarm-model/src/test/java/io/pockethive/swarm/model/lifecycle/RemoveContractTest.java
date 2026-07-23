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
  void successfulResultCannotHideRemainingResourcesOrErrors() {
    RemoveResource queue = new RemoveResource(RemoveResourceType.RABBIT_QUEUE, "ph.alpha.work");

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
        List.of(queue),
        List.of(),
        NOW));
    assertTrue(error.getMessage().contains("remainingResources"));
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
        List.of(),
        NOW));
    assertTrue(error.getMessage().contains("errors"));
  }

  @Test
  void resultCopiesEvidenceCollections() {
    List<RemoveResource> removed = new ArrayList<>();
    removed.add(new RemoveResource(RemoveResourceType.WORKER_RUNTIME, "worker-1"));

    RemoveResult result = RemoveResult.succeeded(
        "alpha", "run-1", "alpha-controller-1", "correlation-1", "idempotency-1", removed, NOW);
    removed.clear();

    assertEquals(1, result.removedResources().size());
    assertThrows(UnsupportedOperationException.class, () -> result.removedResources().clear());
  }
}
