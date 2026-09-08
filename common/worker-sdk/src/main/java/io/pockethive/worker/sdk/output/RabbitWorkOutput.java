package io.pockethive.worker.sdk.output;

import io.pockethive.work.api.WorkItem;
import io.pockethive.worker.sdk.config.RabbitOutputProperties;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.transport.rabbit.RabbitWorkItemConverter;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Publishes worker results to RabbitMQ using {@link RabbitTemplate}.
 * <p>
 * Responsibility: publish canonical Work envelopes to its immutable Rabbit destination.
 * Must not: read Control Plane routing or re-resolve mutable destinations at publish time.
 * Contract: RESP-WORK-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-transport.
 */
public final class RabbitWorkOutput implements WorkOutput {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String routingKey;
    private final MessageDeliveryMode deliveryMode;
    private final RabbitWorkItemConverter converter = new RabbitWorkItemConverter();

    public RabbitWorkOutput(RabbitTemplate rabbitTemplate, RabbitOutputProperties properties) {
        this.rabbitTemplate = java.util.Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
        this.exchange = java.util.Objects.requireNonNull(properties.getExchange(), "exchange");
        this.routingKey = java.util.Objects.requireNonNull(properties.getRoutingKey(), "routingKey");
        this.deliveryMode = properties.isPersistent() ? MessageDeliveryMode.PERSISTENT : MessageDeliveryMode.NON_PERSISTENT;
    }

    @Override
    public void publish(WorkItem item, WorkerDefinition definition) {
        Message outbound = converter.toMessage(item);
        MessageProperties props = outbound.getMessageProperties();
        props.setDeliveryMode(deliveryMode);
        if (props.getContentType() == null) {
            props.setContentType(MessageProperties.CONTENT_TYPE_BYTES);
        }
        rabbitTemplate.send(exchange, routingKey, outbound);
    }

}
