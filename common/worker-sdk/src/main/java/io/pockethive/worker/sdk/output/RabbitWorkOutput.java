package io.pockethive.worker.sdk.output;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.config.RabbitOutputProperties;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.transport.rabbit.RabbitWorkItemConverter;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Publishes worker results to RabbitMQ using {@link RabbitTemplate}.
 */
public final class RabbitWorkOutput implements WorkOutput {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitOutputProperties properties;
    private final RabbitWorkItemConverter converter = new RabbitWorkItemConverter();

    public RabbitWorkOutput(RabbitTemplate rabbitTemplate, RabbitOutputProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(WorkItem item, WorkerDefinition definition) {
        WorkIoBindings io = definition.io();
        String exchange = properties.getExchange() != null ? properties.getExchange() : io.outboundExchange();
        String routingKey = properties.getRoutingKey() != null ? properties.getRoutingKey() : io.outboundQueue();
        if (exchange == null || routingKey == null) {
            throw new IllegalStateException("Cannot publish worker result without exchange and routing key");
        }
        Message outbound = converter.toMessage(item);
        MessageProperties props = outbound.getMessageProperties();
        props.setDeliveryMode(properties.isPersistent() ? MessageDeliveryMode.PERSISTENT : MessageDeliveryMode.NON_PERSISTENT);
        if (props.getContentType() == null) {
            props.setContentType(MessageProperties.CONTENT_TYPE_BYTES);
        }
        rabbitTemplate.send(exchange, routingKey, outbound);
    }

}
