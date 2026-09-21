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
    targets.add(new RemoveResource(RemoveResourceType.WORKER_RUNTIME, "worker-1", io.pockethive.swarm.model.lifecycle.ResourcePlane.NONE));

    RemoveResult result = RemoveResult.succeeded(
        "alpha", "run-1", "alpha-controller-1", "correlation-1", "idempotency-1", targets, NOW);
    targets.clear();

    assertEquals(1, result.targetResources().size());
    assertThrows(UnsupportedOperationException.class, () -> result.targetResources().clear());
  }
  @Test
  void jsonRequiresPlaneAndRejectsPlaneIncompatibleWithResourceType() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
        () -> mapper.readValue("{\"type\":\"RABBIT_QUEUE\",\"id\":\"jobs\"}", RemoveResource.class));
    assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
        () -> mapper.readValue("{\"type\":\"RABBIT_QUEUE\",\"id\":\"jobs\",\"plane\":\"NONE\"}", RemoveResource.class));
    assertThrows(IllegalArgumentException.class,
        () -> new RemoveResource(RemoveResourceType.WORKER_RUNTIME, "worker", ResourcePlane.CONTROL));
    var expected = new RemoveResource(RemoveResourceType.RABBIT_QUEUE, "jobs", ResourcePlane.CONTROL);
    assertEquals(expected, mapper.readValue(mapper.writeValueAsString(expected), RemoveResource.class));
    var nativeWork = new RemoveResource(RemoveResourceType.WORK_RESOURCE, "memory://swarm/jobs", ResourcePlane.WORK);
    assertEquals(nativeWork, mapper.readValue(mapper.writeValueAsString(nativeWork), RemoveResource.class));
    assertThrows(IllegalArgumentException.class,
        () -> new RemoveResource(RemoveResourceType.WORK_RESOURCE, "memory://swarm/jobs", ResourcePlane.CONTROL));
    assertThrows(IllegalArgumentException.class,
        () -> new RemoveResource(RemoveResourceType.WORK_RESOURCE, "memory://swarm/jobs", ResourcePlane.NONE));
  }

}
