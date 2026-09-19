package io.pockethive.rabbit.work;

import io.pockethive.rabbit.api.RabbitPublisher;
import io.pockethive.rabbit.work.RabbitOutputProperties;
import io.pockethive.rabbit.work.RabbitWorkOutput;
import io.pockethive.work.api.transport.WorkOutput;
import io.pockethive.work.api.transport.WorkOutputTransportFactory;
import io.pockethive.work.config.WorkerOutputType;
import io.pockethive.work.config.binding.WorkOutputConfig;
/**
 * Responsibility: create the Rabbit output from canonically validated bound settings.
 * Must not: open broker clients or own setting rules.
 * Contract: RESP-WORK-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-transport.
 */
public final class RabbitWorkOutputFactory implements WorkOutputTransportFactory {

    private final RabbitPublisher rabbitTemplate;

    public RabbitWorkOutputFactory(RabbitPublisher rabbitTemplate) {
        this.rabbitTemplate = java.util.Objects.requireNonNull(rabbitTemplate, "rabbitTemplate");
    }

    @Override
    public WorkerOutputType type() { return WorkerOutputType.RABBITMQ; }

    @Override
    public WorkOutput create(WorkOutputConfig config) {
        if (!(config instanceof RabbitOutputProperties properties)) {
            throw new IllegalStateException("Rabbit outputs require RabbitOutputProperties configuration");
        }
        return new RabbitWorkOutput(rabbitTemplate, properties.settings());
    }

}
