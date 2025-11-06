package io.pockethive.logaggregator;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.AbstractConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Aligns RabbitMQ client limits with the large payloads produced while workers run in DEBUG mode.
 * The default 64 MiB ceiling was too restrictive once services started emitting verbose logs.
 */
@Configuration
class RabbitConfiguration {

  private static final Logger log = LoggerFactory.getLogger(RabbitConfiguration.class);

  private final ConnectionFactory connectionFactory;
  private final int maxInboundBytes;

  RabbitConfiguration(
      ConnectionFactory connectionFactory,
      @Value("${pockethive.logs.maxInboundMessageBytes:104857600}") int maxInboundBytes) {
    this.connectionFactory = connectionFactory;
    this.maxInboundBytes = maxInboundBytes;
  }

  @PostConstruct
  void customize() {
    if (connectionFactory instanceof AbstractConnectionFactory abstractFactory) {
      Object rabbit = abstractFactory.getRabbitConnectionFactory();
      if (!invokeSetter(rabbit, "setMaxInboundMessageBodySize", maxInboundBytes)) {
        invokeSetter(rabbit, "setRequestedFrameMax", maxInboundBytes);
      }
    }
  }

  private boolean invokeSetter(Object target, String methodName, int value) {
    try {
      Method method = target.getClass().getMethod(methodName, int.class);
      method.invoke(target, value);
      log.debug("Applied {}={} on {}", methodName, value, target.getClass().getName());
      return true;
    } catch (NoSuchMethodException ex) {
      log.debug("{} not available on {}", methodName, target.getClass().getName());
      return false;
    } catch (IllegalAccessException | InvocationTargetException ex) {
      log.warn("Failed to invoke {} on {}", methodName, target.getClass().getName(), ex);
      return false;
    }
  }
}
