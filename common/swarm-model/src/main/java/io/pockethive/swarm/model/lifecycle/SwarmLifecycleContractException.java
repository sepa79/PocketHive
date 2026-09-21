package io.pockethive.swarm.model.lifecycle;

/** Raised when an inbound lifecycle JSON payload violates its canonical schema. */
public final class SwarmLifecycleContractException extends RuntimeException {

  public SwarmLifecycleContractException(String message) {
    super(message);
  }

  public SwarmLifecycleContractException(String message, Throwable cause) {
    super(message, cause);
  }
}
