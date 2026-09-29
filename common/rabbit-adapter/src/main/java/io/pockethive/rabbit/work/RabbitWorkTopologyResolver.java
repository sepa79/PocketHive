package io.pockethive.rabbit.work;

import io.pockethive.rabbit.api.RabbitResourceNames;
import io.pockethive.rabbit.api.RabbitWorkTopologySettings;
import io.pockethive.rabbit.api.RabbitWorkSettingsBootstrap;
import io.pockethive.topology.work.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
/**
 * Responsibility: resolve one immutable Work topology and its Rabbit environment/status projections.
 * Must not: duplicate physical name formulas, create resources or retain mutable swarm state.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public final class RabbitWorkTopologyResolver implements WorkTopologyResolver {
    private final RabbitResourceNames names;
    private final Function<String, RabbitWorkTopologySettings> settings;

    public RabbitWorkTopologyResolver(RabbitResourceNames names, Function<String, RabbitWorkTopologySettings> settings) {
        this.names = Objects.requireNonNull(names, "names");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override public ResolvedWorkTopology resolve(String swarmId, Set<String> logicalChannels) {
        var supplied = settings.apply(swarmId);
        var configured = names.topologySettings(supplied.queuePrefix(), supplied.hiveExchange());
        String exchange = names.exchangeName(configured.hiveExchange());
        var resources = new ArrayList<WorkResourceIdentity>();
        resources.add(RabbitWorkResourceKind.EXCHANGE.identity(exchange));
        var channels = new LinkedHashMap<String, WorkChannelAddress>();
        for (String logical : logicalChannels) {
            var address = names.address(exchange, configured.queuePrefix(), logical);
            var resource = RabbitWorkResourceKind.QUEUE.identity(address.queue());
            resources.add(resource);
            channels.put(logical, new WorkChannelAddress(address.queue(), address.routingKey(),
                Map.of(RabbitWorkSettingsBootstrap.INPUT_QUEUE_ENV, address.queue(),
                    RabbitWorkSettingsBootstrap.OUTPUT_EXCHANGE_ENV, exchange),
                Map.of(RabbitWorkSettingsBootstrap.OUTPUT_ROUTING_KEY_ENV, address.routingKey(),
                    RabbitWorkSettingsBootstrap.OUTPUT_EXCHANGE_ENV, exchange),
                Map.of("queue", address.queue()), Map.of("routingKey", address.routingKey()), resource));
        }
        return new ResolvedWorkTopology(channels, resources, io.pockethive.rabbit.api.RabbitControllerTopologyEnvironment.encode(configured),
            Map.of("exchange", exchange));
    }
}
