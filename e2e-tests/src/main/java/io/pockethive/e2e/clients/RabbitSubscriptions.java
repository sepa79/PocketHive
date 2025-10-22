package io.pockethive.e2e.clients;

import java.util.Objects;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.lang.Nullable;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandler;

import io.pockethive.e2e.config.EnvironmentConfig.ControlPlaneSettings;
import io.pockethive.e2e.config.EnvironmentConfig.RabbitMqSettings;
import io.pockethive.e2e.support.ControlPlaneEvents;

/**
 * Placeholder messaging client. Actual subscription helpers will be introduced in later phases.
 */
public final class RabbitSubscriptions {

  private final ConnectionFactory connectionFactory;
  private final String controlExchange;

  private RabbitSubscriptions(ConnectionFactory connectionFactory, String controlExchange) {
    this.connectionFactory = connectionFactory;
    this.controlExchange = controlExchange;
  }

  public static RabbitSubscriptions from(RabbitMqSettings settings, ControlPlaneSettings controlPlane) {
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(controlPlane, "controlPlane");
    CachingConnectionFactory factory = new CachingConnectionFactory();
    factory.setHost(settings.host());
    factory.setPort(settings.port());
    factory.setUsername(settings.username());
    factory.setPassword(settings.password());
    factory.setVirtualHost(settings.virtualHost());
    return new RabbitSubscriptions(factory, controlPlane.exchange());
  }

  public ConnectionFactory connectionFactory() {
    return connectionFactory;
  }

  public ControlPlaneEvents controlPlaneEvents() {
    return new ControlPlaneEvents(connectionFactory, controlExchange);
  }

  /**
   * Placeholder STOMP bridge for later websocket validation. Implementations will attach a {@link StompSessionHandler}
   * to the nginx proxy once coverage reaches the UI scenarios.
   */
  public StompSession connectStomp(@Nullable StompSessionHandler handler) {
    throw new UnsupportedOperationException("STOMP bridge not yet implemented");
  }
}
