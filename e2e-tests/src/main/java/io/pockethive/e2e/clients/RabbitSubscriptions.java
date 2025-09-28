package io.pockethive.e2e.clients;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.lang.Nullable;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandler;

import io.pockethive.e2e.config.EnvironmentConfig.RabbitMqSettings;

/**
 * Placeholder messaging client. Actual subscription helpers will be introduced in later phases.
 */
public final class RabbitSubscriptions {

  private final ConnectionFactory connectionFactory;

  private RabbitSubscriptions(ConnectionFactory connectionFactory) {
    this.connectionFactory = connectionFactory;
  }

  public static RabbitSubscriptions from(RabbitMqSettings settings) {
    CachingConnectionFactory factory = new CachingConnectionFactory();
    factory.setHost(settings.host());
    factory.setPort(settings.port());
    factory.setUsername(settings.username());
    factory.setPassword(settings.password());
    factory.setVirtualHost(settings.virtualHost());
    return new RabbitSubscriptions(factory);
  }

  public ConnectionFactory connectionFactory() {
    return connectionFactory;
  }

  /**
   * Placeholder STOMP bridge for later websocket validation. Implementations will attach a {@link StompSessionHandler}
   * to the nginx proxy once coverage reaches the UI scenarios.
   */
  public StompSession connectStomp(@Nullable StompSessionHandler handler) {
    throw new UnsupportedOperationException("STOMP bridge not yet implemented");
  }
}
