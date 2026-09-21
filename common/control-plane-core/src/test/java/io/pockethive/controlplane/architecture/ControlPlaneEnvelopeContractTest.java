package io.pockethive.controlplane.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import io.pockethive.control.AlertMessage;
import io.pockethive.control.CommandOutcome;
import io.pockethive.control.CommandResult;
import io.pockethive.control.ControlPlaneEnvelope;
import io.pockethive.control.ControlSignal;
import io.pockethive.control.JournalEvent;
import io.pockethive.control.StatusMetric;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import org.junit.jupiter.api.Test;

class ControlPlaneEnvelopeContractTest {

  @Test
  void transportMessagesCanOnlyCarryCanonicalEnvelopes() {
    assertThat(SignalMessage.class.getRecordComponents()[1].getType()).isEqualTo(ControlPlaneEnvelope.class);
    assertThat(EventMessage.class.getRecordComponents()[1].getType()).isEqualTo(ControlPlaneEnvelope.class);
  }

  @Test
  void canonicalEnvelopeHierarchyIsClosedToServiceLocalDtos() {
    assertThat(ControlPlaneEnvelope.class.isSealed()).isTrue();
    assertThat(ControlPlaneEnvelope.class.getPermittedSubclasses())
        .containsExactlyInAnyOrder(
            AlertMessage.class, CommandOutcome.class, CommandResult.class,
            ControlSignal.class, JournalEvent.class, StatusMetric.class);
  }
}
