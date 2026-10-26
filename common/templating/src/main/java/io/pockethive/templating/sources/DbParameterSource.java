package io.pockethive.templating.sources;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Responsibility: split the existing DB parameter source path and project its variable dependencies.
 * Must not: evaluate Pebble, parse SQL, resolve parameter values or connect to a database.
 * Contract: RESP-DB-QUERY-WORK — docs/architecture/runtime-responsibilities.md#resp-db-query-work.
 */
public record DbParameterSource(String root, String path) {
    public static final String PAYLOAD = "payload";
    public static final String HEADERS = "headers";
    public static final String VARS = "vars";
    public static final String WORKER_IMAGE = "db-query";

    public static DbParameterSource parse(String source) {
        int dot = source.indexOf('.');
        if (dot <= 0 || dot == source.length() - 1)
            throw new IllegalArgumentException("Unsupported DB query param source: " + source);
        return new DbParameterSource(source.substring(0, dot), source.substring(dot + 1));
    }

    public static Set<String> variableNames(Map<?, ?> document) {
        Set<String> names = new LinkedHashSet<>();
        if (document.get("params") instanceof List<?> params) {
            for (Object value : params) {
                if (!(value instanceof Map<?, ?> param) || !(param.get("source") instanceof String source)) continue;
                // Param's existing boundary normalization trims before resolving.
                DbParameterSource reference = parse(source.trim());
                if (VARS.equals(reference.root())) names.add(reference.path().split("\\.")[0]);
            }
        }
        return Set.copyOf(names);
    }
}
