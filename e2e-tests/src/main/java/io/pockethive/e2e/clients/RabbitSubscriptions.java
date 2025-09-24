package io.pockethive.e2e.clients;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.lang.Nullable;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandler;

/**
 * Placeholder messaging client. Actual subscription helpers will be introduced in later phases.
 */
public final class RabbitSubscriptions {

  private final ConnectionFactory connectionFactory;

  private RabbitSubscriptions(ConnectionFactory connectionFactory) {
    this.connectionFactory = connectionFactory;
  }

  public static RabbitSubscriptions fromUri(String uri) {
    Objects.requireNonNull(uri, "uri");
    try {
      CachingConnectionFactory factory = new CachingConnectionFactory();
      factory.setUri(new URI(uri));
      return new RabbitSubscriptions(factory);
    } catch (URISyntaxException ex) {
      throw new IllegalStateException("Invalid AMQP URI provided for RabbitMQ subscriptions", ex);
    }
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
