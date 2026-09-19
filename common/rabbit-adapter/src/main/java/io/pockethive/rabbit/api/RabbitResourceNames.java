package io.pockethive.rabbit.api;

import io.pockethive.topology.control.ControlResourceNamesPort;
import java.util.List;
import java.util.ArrayList;

/**
 * Responsibility: own physical Rabbit names and resolved Work/STOMP addresses, including Work routing realization.
 * Must not: read process configuration, validate adapter tuning or access infrastructure.
 * Contract: RESP-WORK-RESOURCE-NAMES — docs/architecture/runtime-responsibilities.md#resp-work-resource-names.
 */
public final class RabbitResourceNames implements ControlResourceNamesPort {
    private static final String SWARM_PREFIX = "ph.";
    private static final String HIVE_SUFFIX = ".hive";

    public static RabbitStompSubscription controlStompSubscription(String exchange) {
        String prefix = "/exchange/" + name(exchange, "control exchange") + "/";
        return new RabbitStompSubscription(prefix + "#", prefix);
    }

    public RabbitWorkTopologySettings forSwarm(String swarmId) {
        String prefix = SWARM_PREFIX + name(swarmId, "swarm id");
        return new RabbitWorkTopologySettings(prefix, prefix + HIVE_SUFFIX);
    }

    public RabbitWorkTopologySettings topologySettings(String queuePrefix, String hiveExchange) {
        return new RabbitWorkTopologySettings(name(queuePrefix, "traffic queue prefix"), exchangeName(hiveExchange));
    }

    public RabbitWorkAddress address(String exchange, String prefix, String suffix) {
        String queue = queueName(prefix, suffix);
        return new RabbitWorkAddress(exchangeName(exchange), queue, queue);
    }

    public String exchangeName(String configuredName) {
        return name(configuredName, "traffic exchange");
    }

    private static String name(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be null or blank");
        return value.trim();
    }

    public String queueName(String prefix, String suffix) {
        return name(prefix, "traffic queue prefix") + "." + name(suffix, "traffic queue suffix");
    }
    @Override
    public String workerControlQueue(String prefix, String swarmId, String role, String instanceId) {
        return segment(prefix) + "." + segment(swarmId) + "." + segment(role) + "." + segment(instanceId);
    }

    @Override
    public String managerControlQueue(String prefix, String role, String instanceId) {
        return segment(prefix) + "." + segment(role) + "." + segment(instanceId);
    }

    @Override
    public String controllerStatusQueue(String prefix, String instanceId) {
        return segment(prefix) + ".orchestrator-status." + segment(instanceId);
    }

    private static String segment(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Queue name segment must not be blank");
        return value;
    }

    public static String debugTapQueue(String swarmId, String role, String tapId) {
        return "ph.debug.%s.%s.%s".formatted(segment(swarmId), segment(role), segment(tapId).substring(0, 8));
    }

    @Override
    public String swarmControllerQueue(String baseQueue, String swarmId, String role, String instanceId) {
        if (baseQueue == null || baseQueue.isBlank()) {
            throw new IllegalArgumentException("baseQueue must not be blank");
        }
        if (swarmId == null || swarmId.isBlank()) {
            throw new IllegalArgumentException("swarmId must not be blank");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId must not be blank");
        }

        List<String> segments = new ArrayList<>();
        for (String segment : baseQueue.split("\\.")) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        if (!segments.contains(swarmId)) {
            segments.add(swarmId);
        }
        segments.add(role);
        segments.add(instanceId);
        return String.join(".", segments);
    }


}
