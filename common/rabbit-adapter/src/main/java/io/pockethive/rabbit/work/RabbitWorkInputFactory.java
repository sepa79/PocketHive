package io.pockethive.rabbit.work;

import io.pockethive.rabbit.api.RabbitListeners;
import io.pockethive.work.api.transport.WorkInputChannel;
import io.pockethive.work.api.transport.WorkInputTransportFactory;
import io.pockethive.work.config.WorkerInputType;
import io.pockethive.work.config.binding.WorkInputConfig;
import java.util.Objects;
/**
 * Responsibility: create a Rabbit Work input channel from canonically validated bound settings.
 * Must not: read SDK state, dispatch workers or create an alternate settings parser.
 * Contract: RESP-WORK-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-transport.
 */
public final class RabbitWorkInputFactory implements WorkInputTransportFactory {
    private final RabbitListeners listeners;
    public RabbitWorkInputFactory(RabbitListeners listeners) { this.listeners = Objects.requireNonNull(listeners); }
    @Override public WorkerInputType type() { return WorkerInputType.RABBITMQ; }
    @Override public WorkInputChannel create(String workerName, WorkInputConfig config) {
        if (!(config instanceof RabbitInputProperties properties)) throw new IllegalArgumentException("Rabbit input settings required");
        return new RabbitWorkInputChannel(listeners, workerName, properties.settings());
    }
}
