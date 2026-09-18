package io.pockethive.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.swarm.model.lifecycle.OperationType;
import org.junit.jupiter.api.Test;

class ControlPlaneOperationsTest {

  @Test
  void mapsEveryOperationTypeBothWays() {
    for (OperationType type : OperationType.values()) {
      String signal = ControlPlaneOperations.signalForType(type);
      assertThat(ControlPlaneOperations.typeForSignal(signal)).isEqualTo(type);
    }
  }

  @Test
  void rejectsSignalsThatAreNotOperationContracts() {
    assertThatThrownBy(() -> ControlPlaneOperations.typeForSignal(ControlPlaneSignals.STATUS_REQUEST))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
