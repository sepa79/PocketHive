package io.pockethive.work.config;

import java.util.Collection;
import java.util.Locale;

/**
 * Responsibility: resolve boundary text against explicitly declared IO type identities.
 * Must not: infer adapter selection, supply settings or instantiate providers.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/work-plane-boundaries.md#10-remaining-workplane-isolation-target--rabbit-plus-a-test-adapter.
 */
public final class WorkIoTypeParser {
    private WorkIoTypeParser() { }

    public static WorkIoType parse(String value, Collection<? extends WorkIoType> declared) {
        String name = normalize(value);
        var matches = declared.stream().filter(type -> type.name().equals(name)).distinct().toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("Unsupported type.");
        if (matches.size() != 1) throw new IllegalArgumentException("Multiple type definitions are registered.");
        return matches.getFirst();
    }

    /** Compares a bootstrap selector without validating the complete provider catalogue. */
    public static boolean matches(String value, WorkIoType type) {
        return value != null && !value.isBlank() && type.name().equals(normalize(value));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Type must be configured as nonblank text.");
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
