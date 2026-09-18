package io.pockethive.swarm.model.lifecycle;

import java.util.Map;
import java.util.Objects;

public record TerminalResult(
    TerminalStatus status,
    boolean retryable,
    Map<String, Object> context
) {

  public TerminalResult {
    status = Objects.requireNonNull(status, "status");
    context = ContractValues.immutableContext(context);
  }
}
