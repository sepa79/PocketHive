package io.pockethive.rabbit.work;

import io.pockethive.rabbit.api.*;
import io.pockethive.topology.work.*;
import java.util.Objects;

/**
 * Responsibility: create Rabbit Work captures from owner-resolved channel addresses.
 * Must not: reconstruct source names, retain samples or select CONTROL resources.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public final class RabbitWorkDebugTaps implements WorkDebugTaps {
    private final RabbitResources resources;
    private final RabbitReceiver receiver;

    public RabbitWorkDebugTaps(RabbitResources resources, RabbitReceiver receiver) {
        this.resources = Objects.requireNonNull(resources);
        this.receiver = Objects.requireNonNull(receiver);
    }

    @Override public WorkDebugTap open(String swarmId, String role, String tapId, WorkChannelAddress source,
                                      int ttlSeconds, int maxItems) {
        RabbitWorkResourceKind.require(source.resource());
        String exchange = Objects.requireNonNull(source.outputEnvironment().get(RabbitWorkSettingsBootstrap.OUTPUT_EXCHANGE_ENV),
            "Resolved Rabbit source exchange");
        String queue = RabbitResourceNames.debugTapQueue(swarmId, role, tapId);
        var spec = RabbitDebugTapSpec.create(queue, exchange, source.outputAddress(), ttlSeconds, maxItems);
        resources.declareQueue(spec.queue());
        resources.bind(spec.binding());
        return new RabbitWorkDebugTap(exchange, source.outputAddress(), queue, resources, receiver);
    }
}
