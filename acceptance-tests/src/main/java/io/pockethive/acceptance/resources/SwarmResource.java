package io.pockethive.acceptance.resources;

import io.pockethive.acceptance.api.ApiException;
import io.pockethive.acceptance.api.ControlReceiptMismatchException;
import io.pockethive.acceptance.api.SwarmApi;
import io.pockethive.acceptance.api.SwarmManagementApi;
import io.pockethive.acceptance.config.OperationLimits;
import io.pockethive.acceptance.operations.OperationAwaiter;
import io.pockethive.swarm.model.lifecycle.ControlRequest;
import io.pockethive.swarm.model.lifecycle.ControlResponse;
import io.pockethive.swarm.model.lifecycle.OperationType;
import io.pockethive.swarm.model.lifecycle.SwarmCreateRequest;
import io.pockethive.swarm.model.lifecycle.SwarmOperation;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Responsibility: own one test swarm receipt and verify removal, including commands by an explicit requesting actor.
 * Must not: guess ownership, retry mutations or implement swarm convergence.
 * Contract: RESP-ACCEPTANCE-RESOURCES — docs/architecture/acceptance-tests.md#resp-acceptance-resources.
 */
public final class SwarmResource implements AutoCloseable {
  private final String id;
  private final SwarmApi api;
  private final OperationAwaiter operations;
  private final OperationLimits limits;
  private AcquisitionState acquisition = AcquisitionState.NOT_REQUESTED;
  // Null means no receipt/operation has been acquired. Non-null type with null receipt is unconfirmed dispatch.
  private OperationType pendingType;
  private ControlResponse pending;
  private String runId;
  private SwarmOperation removal;

  public SwarmResource(String id, SwarmApi api, OperationAwaiter operations, OperationLimits limits) {
    this.id = id; this.api = api; this.operations = operations; this.limits = limits;
  }
  public String id() { return id; }
  public String runId() {
    if (runId == null) throw new IllegalStateException("No observed run for " + id);
    return runId;
  }
  public SwarmOperation removal() {
    if (removal == null) throw new IllegalStateException("No verified removal for " + id);
    return removal;
  }

  public SwarmOperation create(SwarmCreateRequest request) throws IOException, InterruptedException {
    return create(request, api);
  }

  public SwarmOperation create(SwarmCreateRequest request, SwarmApi requester) throws IOException, InterruptedException {
    if (acquisition != AcquisitionState.NOT_REQUESTED) throw new IllegalStateException("Create already attempted");
    acquisition = AcquisitionState.UNCONFIRMED;
    return execute(OperationType.CREATE, () -> requester.create(id, request));
  }

  public SwarmOperation start() throws IOException, InterruptedException {
    requireAcquired();
    settleBeforeRemoval();
    return execute(OperationType.START,
        () -> api.start(id, new ControlRequest(UUID.randomUUID().toString())));
  }

  public SwarmOperation stop() throws IOException, InterruptedException {
    return stop(api);
  }

  public SwarmOperation stop(SwarmApi requester) throws IOException, InterruptedException {
    requireAcquired();
    settleBeforeRemoval();
    return execute(OperationType.STOP,
        () -> requester.stop(id, new ControlRequest(UUID.randomUUID().toString())));
  }

  public SwarmOperation managerEnabled(SwarmManagementApi requester, String instance, boolean enabled)
      throws IOException, InterruptedException {
    requireAcquired();
    settleBeforeRemoval();
    return execute(OperationType.CONFIG_UPDATE,
        () -> requester.managerEnabled(id, instance, UUID.randomUUID().toString(), enabled));
  }
  public SwarmOperation controllerConfig(SwarmManagementApi requester, String instance, java.util.Map<String, Object> patch)
      throws IOException, InterruptedException {
    requireAcquired();
    settleBeforeRemoval();
    return execute(OperationType.CONFIG_UPDATE,
        () -> requester.controllerConfig(id, instance, UUID.randomUUID().toString(), patch));
  }

  public SwarmOperation remove() throws IOException, InterruptedException {
    if (acquisition == AcquisitionState.RELEASED) return removal();
    requireAcquired();
    if (pendingType != OperationType.REMOVE) {
      settleBeforeRemoval();
      return execute(OperationType.REMOVE,
          () -> api.remove(id, new ControlRequest(UUID.randomUUID().toString())));
    }
    // A prior remove attempt failed/timed out. Observe it once; never reissue it or grant a new wait budget.
    SwarmOperation current = observedPending();
    if (!current.terminal()) throw blocked(current);
    return verifyRemoval(current);
  }

  private SwarmOperation verifyRemoval(SwarmOperation result) throws IOException, InterruptedException {
    checkRun(result);
    OperationAwaiter.requireSucceeded(result);
    var context = result.terminalResult().context();
    if (!(context.get("removedResources") instanceof List<?> removed) || removed.isEmpty()
        || !(context.get("remainingResources") instanceof List<?> remaining) || !remaining.isEmpty()
        || !(context.get("errors") instanceof List<?> errors) || !errors.isEmpty()) {
      throw new AssertionError("Remove lacks complete successful cleanup evidence for " + id
          + " operation " + result.correlationId() + ": " + context);
    }
    api.requireAbsent(id);
    removal = result;
    acquisition = AcquisitionState.RELEASED;
    pending = null;
    pendingType = null;
    return result;
  }

  private SwarmOperation execute(OperationType type, ControlCommand command) throws IOException, InterruptedException {
    pendingType = type;
    ControlReceiptMismatchException mismatch = null;
    try {
      pending = command.send();
    } catch (ControlReceiptMismatchException failure) {
      pending = failure.receipt();
      mismatch = failure;
    } catch (ApiException failure) {
      int status = failure.response().status();
      if (type == OperationType.CREATE && status >= 400 && status < 500 && status != 408) {
        acquisition = AcquisitionState.REJECTED;
        pendingType = null;
      } else if ((type == OperationType.STOP || type == OperationType.CONFIG_UPDATE) && status == 403) {
        // Authorization rejected the command before dispatch; admin cleanup remains available.
        pendingType = null;
        pending = null;
      }
      throw failure;
    }
    if (type == OperationType.CREATE) acquisition = AcquisitionState.ACQUIRED;
    SwarmOperation result;
    try {
      result = finishCommand();
    } catch (IOException | InterruptedException | RuntimeException | AssertionError failure) {
      if (mismatch != null) {
        mismatch.addSuppressed(failure);
        throw mismatch;
      }
      throw failure;
    }
    if (mismatch != null) throw mismatch;
    return result;
  }

  private SwarmOperation finishCommand() throws IOException, InterruptedException {
    SwarmOperation result = operations.terminal(id, pendingType, pending);
    if (pendingType == OperationType.REMOVE) return verifyRemoval(result);
    checkRun(result);
    pending = null;
    pendingType = null;
    OperationAwaiter.requireSucceeded(result);
    return result;
  }

  private void checkRun(SwarmOperation result) {
    if (runId == null) runId = result.runtime().runId();
    else if (!runId.equals(result.runtime().runId())) {
      throw new AssertionError("Operation belongs to a different run for " + id);
    }
  }

  private void settleBeforeRemoval() throws IOException, InterruptedException {
    if (pendingType == null) return;
    SwarmOperation result = observedPending();
    if (!result.terminal()) throw blocked(result);
    checkRun(result);
    pending = null;
    pendingType = null;
  }

  private SwarmOperation observedPending() throws IOException, InterruptedException {
    if (pending == null) {
      throw new AssertionError("Unconfirmed " + pendingType + " request for owned swarm " + id
          + "; no accepted operation receipt, cleanup cannot be verified");
    }
    return operations.observe(id, pendingType, pending, limits.request());
  }

  private AssertionError blocked(SwarmOperation operation) {
    return new AssertionError("Cleanup/action blocked by active " + operation.type()
        + " operation " + operation.correlationId() + " for " + id);
  }

  private void requireAcquired() {
    if (acquisition != AcquisitionState.ACQUIRED) {
      throw new IllegalStateException("Swarm ownership is " + acquisition + " for " + id);
    }
  }

  @Override public void close() throws IOException, InterruptedException {
    switch (acquisition) {
      case NOT_REQUESTED, REJECTED, RELEASED -> { }
      case UNCONFIRMED -> throw new AssertionError("Create outcome unconfirmed for " + id
          + "; no receipt, manual inspection through API required");
      case ACQUIRED -> remove();
    }
  }
}
