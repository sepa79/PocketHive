package io.pockethive.acceptance.operations;

import io.pockethive.acceptance.api.SwarmApi;
import io.pockethive.acceptance.config.OperationLimits;
import io.pockethive.acceptance.evidence.RunEvidence;
import io.pockethive.swarm.model.lifecycle.ControlResponse;
import io.pockethive.swarm.model.lifecycle.OperationState;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.SwarmOperation;
import java.io.IOException;
import java.time.Duration;

/**
 * Responsibility: observe the exact accepted operation until its canonical terminal result.
 * Must not: infer success, retry commands or accept unrelated evidence.
 * Contract: RESP-ACCEPTANCE-OPERATIONS — docs/architecture/acceptance-tests.md#resp-acceptance-operations.
 */
public final class OperationAwaiter {
  private final SwarmApi api;
  private final OperationLimits limits;
  private final RunEvidence evidence;
  public OperationAwaiter(SwarmApi api, OperationLimits limits, RunEvidence evidence) {
    this.api = api; this.limits = limits; this.evidence = evidence;
  }
  public SwarmOperation terminal(String swarmId, OperationType type, ControlResponse receipt)
      throws IOException, InterruptedException {
    Duration offered = Duration.ofMillis(receipt.timeoutMs());
    var deadline = new Deadline(offered.compareTo(limits.operation()) < 0 ? offered : limits.operation(),
        type + " " + swarmId + " operation " + receipt.correlationId());
    while (true) {
      SwarmOperation operation = observe(swarmId, type, receipt, deadline.remaining());
      if (operation.terminal()) return operation;
      deadline.pause(limits.poll());
    }
  }
  public SwarmOperation observe(String swarmId, OperationType type, ControlResponse receipt, Duration budget)
      throws IOException, InterruptedException {
    SwarmOperation result = api.operation(receipt.operationUrl(), budget);
    if (!swarmId.equals(result.swarmId()) || type != result.type()
        || !receipt.correlationId().equals(result.correlationId())
        || !receipt.idempotencyKey().equals(result.idempotencyKey())) {
      throw new AssertionError("Unrelated operation evidence for " + swarmId + " " + receipt.correlationId());
    }
    evidence.record("operation-" + receipt.correlationId(), result);
    return result;
  }
  public static void requireSucceeded(SwarmOperation operation) {
    if (operation.state() != OperationState.SUCCEEDED) {
      throw new AssertionError(operation.type() + " " + operation.swarmId() + " operation "
          + operation.correlationId() + " ended " + operation.state()
          + "; context=" + operation.terminalResult().context());
    }
  }
}
