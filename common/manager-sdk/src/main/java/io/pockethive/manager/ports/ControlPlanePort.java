package io.pockethive.manager.ports;

import io.pockethive.control.ControlPlaneEnvelope;

/**
 * Port used by the manager runtime core to publish control-plane signals and events.
 */
public interface ControlPlanePort {

  /**
   * Publish a control-plane signal (e.g. swarm-start, swarm-stop, config-update).
   *
   * @param routingKey resolved routing key
   * @param payload    canonical envelope
   */
  void publishSignal(String routingKey, ControlPlaneEnvelope payload);

  /**
   * Publish a control-plane event (e.g. status-full, status-delta).
   *
   * @param routingKey resolved routing key
   * @param payload    canonical envelope
   */
  void publishEvent(String routingKey, ControlPlaneEnvelope payload);
}
