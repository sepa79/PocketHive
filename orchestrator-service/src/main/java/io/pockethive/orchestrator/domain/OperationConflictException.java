package io.pockethive.orchestrator.domain;

import io.pockethive.swarm.model.lifecycle.SwarmOperation;
import java.util.Objects;

public final class OperationConflictException extends RuntimeException {

  private final SwarmOperation activeOperation;

  public OperationConflictException(SwarmOperation activeOperation) {
    super("Swarm already has active lifecycle operation "
        + Objects.requireNonNull(activeOperation, "activeOperation").correlationId());
    this.activeOperation = activeOperation;
  }

  public SwarmOperation activeOperation() {
    return activeOperation;
  }
}
