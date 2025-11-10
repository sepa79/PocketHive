package io.pockethive.worker.sdk.output;

import io.pockethive.worker.sdk.api.WorkResult;
import io.pockethive.worker.sdk.config.RabbitOutputProperties;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
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

    public RabbitWorkOutput(RabbitTemplate rabbitTemplate, RabbitOutputProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(WorkResult.Message result, WorkerDefinition definition) {
        String exchange = properties.getExchange() != null ? properties.getExchange() : definition.exchange();
        String routingKey = properties.getRoutingKey() != null ? properties.getRoutingKey() : definition.outQueue();
        if (exchange == null || routingKey == null) {
            throw new IllegalStateException("Cannot publish worker result without exchange and routing key");
        }
        MessageProperties props = new MessageProperties();
        props.setDeliveryMode(properties.isPersistent() ? MessageDeliveryMode.PERSISTENT : MessageDeliveryMode.NON_PERSISTENT);
        applyHeaders(result, props);
        if (props.getContentType() == null) {
            props.setContentType(MessageProperties.CONTENT_TYPE_BYTES);
        }
        Message outbound = new Message(result.value().body(), props);
        rabbitTemplate.send(exchange, routingKey, outbound);
    }

    private void applyHeaders(WorkResult.Message result, MessageProperties props) {
        var headers = result.value().headers();
        if (headers == null || headers.isEmpty()) {
            return;
        }
        headers.forEach(props::setHeader);
        Object explicitContentType = headers.get("content-type");
        if (explicitContentType == null) {
            explicitContentType = headers.get("content_type");
        }
        if (explicitContentType != null) {
            props.setContentType(explicitContentType.toString());
        }
    }
}
