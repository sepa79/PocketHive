package io.pockethive.rabbit.topology;

import io.pockethive.rabbit.api.RabbitBindingSpec;
import io.pockethive.rabbit.api.RabbitExchangeSpec;
import io.pockethive.rabbit.api.RabbitQueueSpec;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

/**
 * Responsibility: map public resource specifications to Spring Rabbit declarations once.
 * Must not: resolve names, add defaults or access a broker.
 * Contract: RESP-RABBIT-RESOURCES — docs/architecture/runtime-responsibilities.md#resp-rabbit-resources.
 */
final class RabbitDeclarations {
    private RabbitDeclarations() { }
    static Queue queue(RabbitQueueSpec queue) {
        return new Queue(queue.name(), queue.durable(), queue.exclusive(), queue.autoDelete(), queue.arguments());
    }
    static TopicExchange exchange(RabbitExchangeSpec exchange) {
        return new TopicExchange(exchange.name(), exchange.durable(), exchange.autoDelete(), exchange.arguments());
    }
    static Binding binding(RabbitBindingSpec binding) {
        return new Binding(binding.queue(), Binding.DestinationType.QUEUE, binding.exchange(), binding.routingKey(), binding.arguments());
    }
}
