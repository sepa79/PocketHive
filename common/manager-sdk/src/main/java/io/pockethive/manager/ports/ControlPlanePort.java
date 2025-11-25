package io.pockethive.manager.ports;

/**
 * Port used by the manager runtime core to publish control-plane signals and events.
 */
public interface ControlPlanePort {

  /**
   * Publish a control-plane signal (e.g. swarm-start, swarm-stop, config-update).
   *
   * @param routingKey resolved routing key
   * @param payload    JSON payload
   */
  void publishSignal(String routingKey, String payload);

  /**
   * Publish a control-plane event (e.g. status-full, status-delta).
   *
   * @param routingKey resolved routing key
   * @param payload    JSON payload
   */
  void publishEvent(String routingKey, String payload);
}

