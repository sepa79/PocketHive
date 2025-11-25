package io.pockethive.swarmcontroller.runtime;

import io.pockethive.controlplane.messaging.ControlPlanePublisher;
import io.pockethive.controlplane.messaging.EventMessage;
import io.pockethive.controlplane.messaging.SignalMessage;
import io.pockethive.manager.ports.ControlPlanePort;
import java.util.Objects;

/**
 * Adapter that bridges {@link ControlPlanePort} to {@link ControlPlanePublisher}.
 */
public final class SwarmControlPlanePortAdapter implements ControlPlanePort {

  private final ControlPlanePublisher publisher;

  public SwarmControlPlanePortAdapter(ControlPlanePublisher publisher) {
    this.publisher = Objects.requireNonNull(publisher, "publisher");
  }

  @Override
  public void publishSignal(String routingKey, String payload) {
    publisher.publishSignal(new SignalMessage(routingKey, payload));
  }

  @Override
  public void publishEvent(String routingKey, String payload) {
    publisher.publishEvent(new EventMessage(routingKey, payload));
  }
}

