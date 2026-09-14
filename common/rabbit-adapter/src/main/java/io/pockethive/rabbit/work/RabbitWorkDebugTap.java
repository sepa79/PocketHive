package io.pockethive.rabbit.work;

import io.pockethive.rabbit.api.RabbitReceiver;
import io.pockethive.rabbit.api.RabbitResources;
import io.pockethive.topology.work.WorkDebugTap;
import java.util.Optional;

/**
 * Responsibility: read and release a single Rabbit Work capture resource.
 * Must not: resolve names, retain samples or own request expiration.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
record RabbitWorkDebugTap(String sourceGroup, String sourceAddress, String captureAddress,
                          RabbitResources resources, RabbitReceiver receiver) implements WorkDebugTap {
    @Override public Optional<byte[]> receive() {
        return receiver.receive(captureAddress).map(message -> message.body() == null ? new byte[0] : message.body());
    }
    @Override public void close() { resources.deleteQueue(captureAddress); }
}
