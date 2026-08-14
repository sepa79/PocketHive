package io.pockethive.controlplane.codec;

/** Raised when a control-plane message does not satisfy the canonical wire contract. */
public final class ControlPlaneContractException extends IllegalArgumentException {

  public ControlPlaneContractException(String message) {
    super(message);
  }

  public ControlPlaneContractException(String message, Throwable cause) {
    super(message, cause);
  }
}
