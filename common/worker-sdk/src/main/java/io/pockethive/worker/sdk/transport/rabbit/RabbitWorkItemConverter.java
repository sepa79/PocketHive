package io.pockethive.worker.sdk.transport.rabbit;

import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkItemJsonCodec;
import io.pockethive.rabbit.api.RabbitMessage;

/**
 * Responsibility: map Work envelopes through their canonical codec into Rabbit API values.
 * Must not: use broker types, choose destinations or create an alternate Work codec.
 * Contract: RESP-WORK-RABBIT-TRANSPORT — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-transport.
 */
public final class RabbitWorkItemConverter {
    private static final WorkItemJsonCodec CODEC = new WorkItemJsonCodec();
    public RabbitMessage toMessage(WorkItem item) { return RabbitMessage.json(CODEC.toJson(item), true); }
    public WorkItem fromMessage(RabbitMessage message) {
        return CODEC.fromJson(message == null ? new byte[0] : message.body());
    }
}
