package io.pockethive.swarmcontroller;

import io.pockethive.control.ControlSignal;
import io.pockethive.controlplane.filesystem.FilesystemSwarmRemoveStore;
import io.pockethive.swarm.model.lifecycle.RemoveError;
import io.pockethive.swarm.model.lifecycle.RemoveRequest;
import io.pockethive.swarm.model.lifecycle.RemoveResult;
import io.pockethive.swarm.model.lifecycle.TerminalStatus;
import io.pockethive.swarmcontroller.config.SwarmControllerProperties;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Responsibility: Execute a canonical filesystem-backed swarm REMOVE request and persist its result.
 * Must not: Consume AMQP messages, publish lifecycle outcomes, or remove Orchestrator-owned resources.
 * Contract: docs/ARCHITECTURE.md §5.4 and docs/ORCHESTRATOR-REST.md swarm-remove lifecycle.
 */
@Component
public class SwarmRemoveCommandHandler {

  private static final Logger log = LoggerFactory.getLogger(SwarmRemoveCommandHandler.class);

  private final SwarmLifecycle lifecycle;
  private final FilesystemSwarmRemoveStore removeStore;
  private final String swarmId;
  private final String instanceId;

  public SwarmRemoveCommandHandler(
      SwarmLifecycle lifecycle,
      FilesystemSwarmRemoveStore removeStore,
      SwarmControllerProperties properties,
      @Qualifier("instanceId") String instanceId) {
    this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    this.removeStore = Objects.requireNonNull(removeStore, "removeStore");
    this.swarmId = Objects.requireNonNull(properties, "properties").getSwarmId();
    this.instanceId = requireText("instanceId", instanceId);
  }

  void handle(ControlSignal signal) {
    Objects.requireNonNull(signal, "signal");
    removeStore.findResult(swarmId, signal.correlationId()).ifPresentOrElse(existing -> {
      if (!existing.idempotencyKey().equals(signal.idempotencyKey())) {
        throw new IllegalStateException("Existing remove result belongs to a different idempotency key");
      }
      log.info("Remove already completed for swarm={} correlation={}", swarmId, signal.correlationId());
    }, () -> execute(signal));
  }

  private void execute(ControlSignal signal) {
    RemoveRequest request = removeStore.loadRequest(swarmId, signal.correlationId());
    requireIdentity(signal, request);
    try {
      removeStore.saveResult(RemoveResult.succeeded(
          request.swarmId(), request.runId(), request.controllerInstance(), request.correlationId(),
          request.idempotencyKey(), lifecycle.remove(), Instant.now()));
    } catch (Exception failure) {
      RemoveResult result = new RemoveResult(
          RemoveResult.SCHEMA,
          request.swarmId(),
          request.runId(),
          request.controllerInstance(),
          request.correlationId(),
          request.idempotencyKey(),
          TerminalStatus.FAILED,
          true,
          List.of(),
          List.of(new RemoveError(
              failure.getClass().getSimpleName(),
              Objects.toString(failure.getMessage(), failure.getClass().getName()),
              null)),
          Instant.now());
      removeStore.saveResult(result);
      log.warn("Remove failed for swarm={} correlation={}", swarmId, signal.correlationId(), failure);
    }
  }

  private void requireIdentity(ControlSignal signal, RemoveRequest request) {
    if (!swarmId.equals(request.swarmId())
        || !instanceId.equals(request.controllerInstance())
        || !signal.correlationId().equals(request.correlationId())
        || !signal.idempotencyKey().equals(request.idempotencyKey())) {
      throw new IllegalArgumentException("Remove signal does not match filesystem request identity");
    }
  }

  private static String requireText(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value.trim();
  }
}
