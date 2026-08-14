package io.pockethive.controlplane;

import io.pockethive.swarm.model.lifecycle.OperationType;
import java.util.Objects;

/** Canonical boundary mapping between wire signal names and domain operation types. */
public final class ControlPlaneOperations {

  private ControlPlaneOperations() {
  }

  public static OperationType typeForSignal(String signal) {
    return switch (Objects.requireNonNull(signal, "signal")) {
      case ControlPlaneSignals.SWARM_CREATE -> OperationType.CREATE;
      case ControlPlaneSignals.SWARM_START -> OperationType.START;
      case ControlPlaneSignals.SWARM_STOP -> OperationType.STOP;
      case ControlPlaneSignals.SWARM_REMOVE -> OperationType.REMOVE;
      case ControlPlaneSignals.CONFIG_UPDATE -> OperationType.CONFIG_UPDATE;
      default -> throw new IllegalArgumentException("Unsupported operation signal: " + signal);
    };
  }

  public static String signalForType(OperationType type) {
    return switch (Objects.requireNonNull(type, "type")) {
      case CREATE -> ControlPlaneSignals.SWARM_CREATE;
      case START -> ControlPlaneSignals.SWARM_START;
      case STOP -> ControlPlaneSignals.SWARM_STOP;
      case REMOVE -> ControlPlaneSignals.SWARM_REMOVE;
      case CONFIG_UPDATE -> ControlPlaneSignals.CONFIG_UPDATE;
    };
  }
}
