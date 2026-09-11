package io.pockethive.rabbit.topology;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ShutdownSignalException;
import io.pockethive.rabbit.api.RabbitBindingSpec;
import io.pockethive.rabbit.api.RabbitExchangeSpec;
import io.pockethive.rabbit.api.RabbitQueueObservation;
import io.pockethive.rabbit.api.RabbitQueueSpec;
import io.pockethive.rabbit.api.RabbitResources;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

/**
 * Responsibility: implement broker resource operations and decode observations for both planes.
 * Must not: decide domain names, authorize removal or suppress broker failures.
 * Contract: docs/architecture/work-plane-boundaries.md#3-ports-owners-and-state-transitions.
 */
public final class SpringRabbitResources implements RabbitResources {
    private final RabbitAdmin admin;

    public SpringRabbitResources(RabbitAdmin admin) {
        this.admin = Objects.requireNonNull(admin, "admin");
    }

    @Override public void declareExchange(RabbitExchangeSpec exchange) {
        admin.declareExchange(RabbitDeclarations.exchange(exchange));
    }
    @Override public void declareQueue(RabbitQueueSpec queue) {
        admin.declareQueue(RabbitDeclarations.queue(queue));
    }
    @Override public void bind(RabbitBindingSpec binding) { admin.declareBinding(RabbitDeclarations.binding(binding)); }
    @Override public void unbind(RabbitBindingSpec binding) { admin.removeBinding(RabbitDeclarations.binding(binding)); }

    @Override public Optional<RabbitQueueObservation> queue(String name) {
        try {
            var result = Objects.requireNonNull(admin.getRabbitTemplate().execute(channel -> channel.queueDeclarePassive(name)),
                "Rabbit passive queue declaration result");
            return Optional.of(new RabbitQueueObservation(result.getMessageCount(), result.getConsumerCount(), OptionalLong.empty()));
        } catch (AmqpException exception) {
            if (isNotFound(exception)) return Optional.empty();
            throw exception;
        }
    }

    @Override public boolean exchangeExists(String name) {
        try {
            admin.getRabbitTemplate().execute(channel -> { channel.exchangeDeclarePassive(name); return null; });
            return true;
        } catch (AmqpException exception) {
            if (isNotFound(exception)) return false;
            throw exception;
        }
    }

    @Override public void deleteQueue(String name) {
        admin.deleteQueue(name);
        if (queue(name).isPresent()) throw new IllegalStateException("Queue still exists after deletion: " + name);
    }
    @Override public void deleteExchange(String name) {
        admin.deleteExchange(name);
        if (exchangeExists(name)) throw new IllegalStateException("Exchange still exists after deletion: " + name);
    }

    private static boolean isNotFound(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof ShutdownSignalException shutdown
                && shutdown.getReason() instanceof AMQP.Channel.Close close && close.getReplyCode() == 404) return true;
        }
        return false;
    }
}
