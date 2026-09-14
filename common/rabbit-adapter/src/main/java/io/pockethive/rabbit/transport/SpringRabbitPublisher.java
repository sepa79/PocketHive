package io.pockethive.rabbit.transport;

import io.pockethive.rabbit.api.RabbitMessage;
import io.pockethive.rabbit.api.RabbitPublisher;
import java.util.Objects;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Responsibility: submit public messages through the Rabbit client.
 * Must not: encode domain envelopes, choose destinations, change delivery semantics or infer ACK from submission.
 * Contract: RESP-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-rabbit-transport.
 */
public final class SpringRabbitPublisher implements RabbitPublisher {
    private final RabbitTemplate template;
    public SpringRabbitPublisher(RabbitTemplate template) { this.template = Objects.requireNonNull(template, "template"); }
    @Override public void send(String exchange, String routingKey, RabbitMessage message) {
        template.send(Objects.requireNonNull(exchange, "exchange"), Objects.requireNonNull(routingKey, "routingKey"),
            RabbitMessages.outbound(message));
    }
}
