package io.pockethive.worker.sdk.output;

import io.pockethive.worker.sdk.config.RabbitOutputProperties;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

public final class RabbitWorkOutputFactory implements WorkOutputFactory {

    private final RabbitTemplate rabbitTemplate;

    public RabbitWorkOutputFactory(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public boolean supports(WorkerDefinition definition) {
        return definition.outputType() == WorkerOutputType.RABBITMQ && rabbitTemplate != null;
    }

    @Override
    public WorkOutput create(WorkerDefinition definition, WorkOutputConfig config) {
        RabbitOutputProperties properties = config instanceof RabbitOutputProperties props ? props : new RabbitOutputProperties();
        if (rabbitTemplate == null) {
            throw new IllegalStateException("RabbitTemplate is required for RabbitMQ outputs");
        }
        return new RabbitWorkOutput(rabbitTemplate, properties);
    }
}
