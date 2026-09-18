package io.pockethive.swarm.model.lifecycle;

public enum OperationState {
  ACCEPTED,
  DISPATCHED,
  SUCCEEDED,
  REJECTED,
  FAILED,
  TIMED_OUT;

  public boolean terminal() {
    return switch (this) {
      case ACCEPTED, DISPATCHED -> false;
      case SUCCEEDED, REJECTED, FAILED, TIMED_OUT -> true;
    };
  }
}
