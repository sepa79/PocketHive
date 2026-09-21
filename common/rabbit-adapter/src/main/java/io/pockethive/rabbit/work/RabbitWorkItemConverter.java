package io.pockethive.rabbit.work;

import io.pockethive.rabbit.api.RabbitMessage;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkItemJsonCodec;
/**
 * Responsibility: decode incoming Rabbit message bodies through the canonical Work envelope codec.
 * Must not: encode outgoing messages, choose persistence/destinations or create an alternate Work codec.
 * Contract: RESP-WORK-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-transport.
 */
public final class RabbitWorkItemConverter {
    private static final WorkItemJsonCodec CODEC = new WorkItemJsonCodec();
    public WorkItem fromMessage(RabbitMessage message) {
        return CODEC.fromJson(message == null ? new byte[0] : message.body());
    }
}
