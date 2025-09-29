package io.pockethive.controlplane.topology;

import java.util.Set;

/**
 * Canonical route templates advertised for a control-plane role.
 */
public record ControlPlaneRouteCatalog(Set<String> configSignals,
                                       Set<String> statusSignals,
                                       Set<String> lifecycleSignals,
                                       Set<String> statusEvents,
                                       Set<String> lifecycleEvents,
                                       Set<String> otherEvents) {

    public static final String INSTANCE_TOKEN = "{instance}";

    public static ControlPlaneRouteCatalog empty() {
        return new ControlPlaneRouteCatalog(Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    public ControlPlaneRouteCatalog {
        configSignals = copyOf(configSignals);
        statusSignals = copyOf(statusSignals);
        lifecycleSignals = copyOf(lifecycleSignals);
        statusEvents = copyOf(statusEvents);
        lifecycleEvents = copyOf(lifecycleEvents);
        otherEvents = copyOf(otherEvents);
    }

    private static Set<String> copyOf(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(source);
    }
}
