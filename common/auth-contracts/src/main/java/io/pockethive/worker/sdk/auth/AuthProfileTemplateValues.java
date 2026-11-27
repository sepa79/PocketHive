package io.pockethive.worker.sdk.auth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Responsibility: traverse auth profile values with opaque environment/file secret references.
 * Must not: read secrets, render templates, validate credentials or activate profiles.
 * Contract: RESP-WORK-AUTH-PROFILE-PREPARATION — docs/architecture/runtime-responsibilities.md#resp-work-auth-profile-preparation.
 */
public final class AuthProfileTemplateValues {
    private AuthProfileTemplateValues() {}

    public static Object map(Object value, Function<String, Object> text,
                             Function<String, Object> environment, Function<String, Object> file) {
        if (value instanceof String source) return text.apply(source);
        if (value instanceof Map<?, ?> fields) {
            if (fields.size() == 1 && fields.containsKey("env"))
                return environment.apply(String.valueOf(fields.get("env")));
            if (fields.size() == 1 && fields.containsKey("file"))
                return file.apply(String.valueOf(fields.get("file")));
            Map<String, Object> mapped = new LinkedHashMap<>();
            fields.forEach((key, child) -> mapped.put(String.valueOf(key), map(child, text, environment, file)));
            return mapped;
        }
        if (value instanceof List<?> values)
            return values.stream().map(child -> map(child, text, environment, file)).toList();
        return value;
    }

    public static List<String> sources(Object value) {
        List<String> sources = new ArrayList<>();
        map(value, source -> { sources.add(source); return source; }, reference -> reference, reference -> reference);
        return List.copyOf(sources);
    }
}
