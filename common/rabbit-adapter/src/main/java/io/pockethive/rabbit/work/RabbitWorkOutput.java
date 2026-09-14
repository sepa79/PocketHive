package io.pockethive.rabbit.work;

import io.pockethive.rabbit.api.RabbitMessage;
import io.pockethive.rabbit.api.RabbitOutputSettings;
import io.pockethive.rabbit.api.RabbitPublisher;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkItemJsonCodec;
import io.pockethive.work.api.transport.WorkOutput;
import java.util.Objects;
/**
 * Responsibility: encode Work results once and publish to the captured Rabbit destination through its API.
 * Must not: use broker clients, read Control Plane defaults or re-resolve mutable destinations.
 * Contract: RESP-WORK-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-transport.
 */
public final class RabbitWorkOutput implements WorkOutput {
    private final RabbitPublisher publisher;
    private final String exchange;
    private final String routingKey;
    private final boolean persistent;
    private final WorkItemJsonCodec codec = new WorkItemJsonCodec();
    public RabbitWorkOutput(RabbitPublisher publisher, RabbitOutputSettings settings) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(settings, "settings");
        this.exchange = settings.exchange();
        this.routingKey = settings.routingKey();
        this.persistent = settings.persistent();
    }
    @Override public void publish(WorkItem item) {
        var message = RabbitMessage.json(codec.toJson(item), persistent);
        publisher.send(exchange, routingKey, message);
    }
}
