package io.pockethive.controlplane.topology;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Control-queue declaration paired with the signal and event bindings it requires.
 */
public record ControlQueueDescriptor(String name,
                                     Set<String> signalBindings,
                                     Set<String> eventBindings) {

    public ControlQueueDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        signalBindings = copyOf(signalBindings);
        eventBindings = copyOf(eventBindings);
    }

    public Set<String> allBindings() {
        LinkedHashSet<String> merged = new LinkedHashSet<>(signalBindings);
        merged.addAll(eventBindings);
        return Set.copyOf(merged);
    }

    private static Set<String> copyOf(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(source);
    }
}
