package io.pockethive.orchestrator.runtime;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitQueueResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitExchangeResource;
import io.pockethive.orchestrator.runtime.RuntimeCleanupPorts.RabbitTopologyPort;
import java.util.Optional;
import java.util.Properties;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Component;

@Component
public class AmqpRabbitTopologyAdapter implements RabbitTopologyPort {
    private final AmqpAdmin amqp;

    public AmqpRabbitTopologyAdapter(AmqpAdmin amqp) {
        this.amqp = amqp;
    }

    @Override
    public Optional<RabbitQueueResource> queue(String name) {
        Properties properties = amqp.getQueueProperties(name);
        if (properties == null) {
            return Optional.empty();
        }
        return Optional.of(new RabbitQueueResource(
            name,
            coerceLong(properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)),
            coerceInt(properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT))));
    }

    @Override
    public Optional<RabbitExchangeResource> exchange(String name) {
        if (!(amqp instanceof RabbitAdmin rabbitAdmin)) {
            throw new IllegalStateException("Rabbit exchange inspection requires RabbitAdmin");
        }
        try {
            rabbitAdmin.getRabbitTemplate().execute(channel -> {
                channel.exchangeDeclarePassive(name);
                return null;
            });
            return Optional.of(new RabbitExchangeResource(name));
        } catch (AmqpException ex) {
            if (isNotFound(ex)) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    @Override
    public void deleteQueue(String name) {
        amqp.deleteQueue(name);
    }

    @Override
    public void deleteExchange(String name) {
        amqp.deleteExchange(name);
    }

    private static long coerceLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Long.parseLong(s.trim());
        }
        return 0L;
    }

    private static int coerceInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Integer.parseInt(s.trim());
        }
        return 0;
    }

    static boolean isNotFound(Throwable throwable) {
        for (Throwable candidate = throwable; candidate != null; candidate = candidate.getCause()) {
            if (candidate instanceof ShutdownSignalException shutdown
                && shutdown.getReason() instanceof AMQP.Channel.Close close
                && close.getReplyCode() == 404) {
                return true;
            }
        }
        return false;
    }
}
