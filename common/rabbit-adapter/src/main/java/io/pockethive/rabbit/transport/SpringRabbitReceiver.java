package io.pockethive.rabbit.transport;

import io.pockethive.rabbit.api.RabbitMessage;
import io.pockethive.rabbit.api.RabbitReceiver;
import java.util.Objects;
import java.util.Optional;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Responsibility: poll an explicitly named queue and expose received message values.
 * Must not: create queues, interpret domain payloads or suppress broker failures.
 * Contract: docs/architecture/work-plane-boundaries.md#5-delivery-and-failure-decisions.
 */
public final class SpringRabbitReceiver implements RabbitReceiver {
    private final RabbitTemplate template;
    public SpringRabbitReceiver(RabbitTemplate template) { this.template = Objects.requireNonNull(template, "template"); }
    @Override public Optional<RabbitMessage> receive(String queue) {
        return Optional.ofNullable(template.receive(Objects.requireNonNull(queue, "queue"))).map(RabbitMessages::inbound);
    }
}
