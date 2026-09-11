package io.pockethive.worker.sdk.output;

import io.pockethive.worker.sdk.config.RabbitOutputProperties;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.rabbit.api.RabbitPublisher;
import org.springframework.core.Ordered;

/**
 * Responsibility: select the Rabbit output bridge for the worker definition.
 * Must not: open broker clients or own setting rules.
 * Contract: RESP-WORK-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-transport.
 */
public final class RabbitWorkOutputFactory implements WorkOutputFactory, Ordered {

    private final RabbitPublisher rabbitTemplate;

    public RabbitWorkOutputFactory(RabbitPublisher rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public boolean supports(WorkerDefinition definition) {
        return definition.outputType() == WorkerOutputType.RABBITMQ && rabbitTemplate != null;
    }

    @Override
    public WorkOutput create(WorkerDefinition definition, WorkOutputConfig config) {
        if (!(config instanceof RabbitOutputProperties properties)) {
            throw new IllegalStateException("Rabbit outputs require RabbitOutputProperties configuration");
        }
        if (rabbitTemplate == null) {
            throw new IllegalStateException("RabbitPublisher is required for RabbitMQ outputs");
        }
        return new RabbitWorkOutput(rabbitTemplate, properties);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
