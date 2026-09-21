package io.pockethive.work.config.projection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Responsibility: project decoded Work configuration into diagnostics with password fields redacted.
 * Must not: mutate accepted configuration, infer secret names or supply adapter settings.
 * Contract: RESP-WORK-CONFIGURATION-DIAGNOSTICS — docs/architecture/runtime-responsibilities.md#resp-work-configuration-diagnostics.
 */
public final class WorkConfigurationRedactor {
    private WorkConfigurationRedactor() { }

    public static <K> Map<K, Object> redact(Map<K, ?> config) {
        Map<K, Object> projection = new LinkedHashMap<>();
        config.forEach((key, value) -> projection.put(key, "password".equals(key) ? "[redacted]" : project(value)));
        return Collections.unmodifiableMap(projection);
    }

    private static Object project(Object value) {
        if (value instanceof Map<?, ?> fields) {
            return redact(fields);
        }
        if (value instanceof List<?> items) {
            return items.stream().map(WorkConfigurationRedactor::project).toList();
        }
        return value;
    }
}
