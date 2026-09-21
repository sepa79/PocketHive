package io.pockethive.rabbit.work;

import io.pockethive.rabbit.api.RabbitInputSettings;
import io.pockethive.rabbit.api.RabbitListeners;
import io.pockethive.rabbit.api.RabbitSubscription;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.transport.WorkDeliveryHandler;
import io.pockethive.work.api.transport.WorkInputChannel;
import io.pockethive.work.api.transport.WorkInputChannelState;
import java.util.Objects;
/**
 * Responsibility: register a resolved Rabbit Work subscription and decode incoming envelopes.
 * Must not: inspect SDK state, dispatch workers, publish results or merge transport headers.
 * Contract: RESP-WORK-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-transport.
 */
public final class RabbitWorkInputChannel implements WorkInputChannel {
    private final RabbitListeners listeners;
    private final RabbitSubscription subscription;
    private final RabbitWorkItemConverter converter = new RabbitWorkItemConverter();

    public RabbitWorkInputChannel(RabbitListeners listeners, String workerName, RabbitInputSettings settings) {
        this.listeners = Objects.requireNonNull(listeners, "listeners");
        Objects.requireNonNull(workerName, "workerName");
        Objects.requireNonNull(settings, "settings");
        this.subscription = new RabbitSubscription(workerName + "Listener", settings.queue(), settings.prefetch(),
            settings.concurrentConsumers(), settings.exclusive(), false);
    }

    @Override
    public void register(WorkDeliveryHandler handler) {
        Objects.requireNonNull(handler, "handler");
        listeners.register(subscription, message -> {
            WorkItem item;
            try {
                item = converter.fromMessage(message);
            } catch (Exception failure) {
                handler.onDecodeFailure(message != null && message.body() != null ? message.body() : new byte[0], failure);
                return;
            }
            handler.onWork(item);
        });
    }

    @Override
    public WorkInputChannelState state() {
        return switch (listeners.state(subscription.id())) {
            case NOT_REGISTERED -> WorkInputChannelState.NOT_REGISTERED;
            case STOPPED -> WorkInputChannelState.STOPPED;
            case RUNNING -> WorkInputChannelState.RUNNING;
        };
    }

    @Override public void start() { listeners.start(subscription.id()); }
    @Override public void stop() { listeners.stop(subscription.id()); }
}
