package io.pockethive.rabbit.api;

import java.util.Map;
import java.util.function.Function;

/**
 * Responsibility: encode and decode the Controller's provisioned Rabbit topology settings.
 * Must not: invent resource names, read process settings or activate Rabbit for another WorkPlane.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public final class RabbitControllerTopologyEnvironment {
    public static final String QUEUE_PREFIX = "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_QUEUE_PREFIX";
    public static final String HIVE_EXCHANGE = "POCKETHIVE_CONTROL_PLANE_SWARM_CONTROLLER_TRAFFIC_HIVE_EXCHANGE";
    private RabbitControllerTopologyEnvironment() { }

    public static RabbitWorkTopologySettings decode(Function<String, String> properties) {
        return new RabbitResourceNames().topologySettings(
            properties.apply("pockethive.control-plane.swarm-controller.traffic.queue-prefix"),
            properties.apply("pockethive.control-plane.swarm-controller.traffic.hive-exchange"));
    }

    public static Map<String, String> encode(RabbitWorkTopologySettings settings) {
        return Map.of(QUEUE_PREFIX, settings.queuePrefix(), HIVE_EXCHANGE, settings.hiveExchange());
    }
}
