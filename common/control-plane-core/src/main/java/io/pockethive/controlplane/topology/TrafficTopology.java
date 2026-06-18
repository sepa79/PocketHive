package io.pockethive.controlplane.topology;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Shared topology naming for swarm traffic queues and the hive exchange.
 */
public record TrafficTopology(String hiveExchange, String queuePrefix) {
    public TrafficTopology {
        hiveExchange = requireText("hiveExchange", hiveExchange);
        queuePrefix = requireText("queuePrefix", queuePrefix);
    }

    public String queueName(String suffix) {
        return queuePrefix + "." + requireText("suffix", suffix);
    }

    public QueueDescriptor queue(String suffix) {
        return new QueueDescriptor(queueName(suffix), Set.of());
    }

    public List<String> queueNames(Collection<String> suffixes) {
        if (suffixes == null || suffixes.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String suffix : suffixes) {
            names.add(queueName(suffix));
        }
        return List.copyOf(names);
    }

    private static String requireText(String name, String value) {
        Objects.requireNonNull(name, "name");
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return trimmed;
    }
}
