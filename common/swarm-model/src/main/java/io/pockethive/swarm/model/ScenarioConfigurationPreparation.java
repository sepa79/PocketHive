package io.pockethive.swarm.model;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Responsibility: prepare authored worker configuration using the selected variables and SUT.
 * Must not: select profiles, render per-message templates, bind worker types or perform IO.
 * Contract: RESP-SCENARIO-CONFIGURATION-PREPARATION — docs/architecture/runtime-responsibilities.md#resp-scenario-configuration-preparation.
 */
public final class ScenarioConfigurationPreparation {
    private static final Pattern BASE_URL_TEMPLATE =
        Pattern.compile("\\{\\{\\s*sut\\.endpoints\\['([^']+)'\\]\\.baseUrl\\s*}}(.*)");
    private static final Pattern VARS_TEMPLATE =
        Pattern.compile("\\{\\{\\s*vars\\.([A-Za-z0-9_.-]*)\\s*}}");

    private ScenarioConfigurationPreparation() { }

    public static Map<String, Object> applyVariables(Map<String, Object> config, Map<String, Object> vars) {
        if (vars == null || vars.isEmpty()) return config;
        return renderVariables(addScenarioVars(config, vars), vars);
    }

    /** Bootstrap references use exactly the substitution matcher, not the Pebble grammar. */
    public static Set<String> variableNames(Map<String, Object> config) {
        return variableNames(config, "", (field, message) -> { throw new IllegalArgumentException(message); });
    }

    public static Set<String> variableNames(Map<String, Object> config, String path,
                                            BiConsumer<String, String> invalid) {
        Set<String> names = new LinkedHashSet<>();
        if (config != null) config.forEach((key, value) -> {
            if (!"vars".equals(key)) collectVariableNames(value, fieldPath(path, key), names, invalid);
        });
        return java.util.Collections.unmodifiableSet(names);
    }

    public static boolean canResolveTypedValue(String source, boolean requireWholeValue) {
        Matcher matcher = VARS_TEMPLATE.matcher(source.trim());
        if (matcher.matches()) {
            variablePath(matcher.group(1));
            return true;
        }
        if (requireWholeValue || !matcher.find()) return false;
        do {
            variablePath(matcher.group(1));
        } while (matcher.find());
        String literal = matcher.replaceAll("");
        return !literal.contains("{{") && !literal.contains("{%");
    }

    private static String fieldPath(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    private static void collectVariableNames(Object value, String field, Set<String> names,
                                             BiConsumer<String, String> invalid) {
        if (value instanceof String source) {
            Matcher matcher = VARS_TEMPLATE.matcher(source);
            while (matcher.find()) {
                try {
                    names.add(variablePath(matcher.group(1))[0]);
                } catch (IllegalArgumentException failure) {
                    invalid.accept(field, failure.getMessage());
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> collectVariableNames(child, fieldPath(field, String.valueOf(key)), names, invalid));
        } else if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                collectVariableNames(list.get(index), field + "[" + index + "]", names, invalid);
            }
        }
    }

    private static Map<String, Object> addScenarioVars(Map<String, Object> config, Map<String, Object> vars) {
        if (vars == null || vars.isEmpty()) {
            return config;
        }
        Map<String, Object> result = (config == null || config.isEmpty())
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(config);
        // Reserved key for scenario variables: config.vars (map).
        // Workers are expected to propagate this into the WorkItem headers so Pebble/SpEL templates can reference it as `vars.*`.
        result.put("vars", vars);
        return Map.copyOf(result);
    }

    private static Map<String, Object> renderVariables(Map<String, Object> config, Map<String, Object> vars) {
        if (config == null || config.isEmpty() || vars == null || vars.isEmpty()) {
            return config;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        config.forEach((key, value) -> {
            if ("vars".equals(key)) {
                result.put(key, value);
            } else {
                result.put(key, renderScenarioVarValue(value, vars));
            }
        });
        return Map.copyOf(result);
    }

    private static Object renderScenarioVarValue(Object value, Map<String, Object> vars) {
        if (value instanceof String text) {
            return renderScenarioVarString(text, vars);
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            rawMap.forEach((key, nestedValue) -> {
                if (key != null) {
                    rendered.put(key.toString(), renderScenarioVarValue(nestedValue, vars));
                }
            });
            return Map.copyOf(rendered);
        }
        if (value instanceof List<?> rawList) {
            return rawList.stream()
                .map(item -> renderScenarioVarValue(item, vars))
                .toList();
        }
        return value;
    }

    private static Object renderScenarioVarString(String text, Map<String, Object> vars) {
        Matcher exact = VARS_TEMPLATE.matcher(text.trim());
        if (exact.matches()) {
            return resolveScenarioVar(vars, exact.group(1));
        }

        Matcher matcher = VARS_TEMPLATE.matcher(text);
        StringBuffer rendered = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            Object value = resolveScenarioVar(vars, matcher.group(1));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(Objects.toString(value, "")));
            changed = true;
        }
        if (!changed) {
            return text;
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    @SuppressWarnings("unchecked")
    private static Object resolveScenarioVar(Map<String, Object> vars, String path) {
        Object current = vars;
        for (String part : variablePath(path)) {
            if (!(current instanceof Map<?, ?> rawMap) || !rawMap.containsKey(part)) {
                throw new IllegalStateException("Unknown scenario variable '%s'".formatted(path));
            }
            current = ((Map<String, Object>) rawMap).get(part);
        }
        if (current == null) {
            throw new IllegalStateException("Scenario variable '%s' is null".formatted(path));
        }
        return current;
    }

    private static String[] variablePath(String path) {
        String[] parts = path.split("\\.", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("Scenario variable path contains an empty segment.");
            }
        }
        return parts;
    }

    public static Map<String, Object> applySut(Map<String, Object> config, SutEnvironment sutEnvironment) {
        Object baseUrlObj = config.get("baseUrl");
        if (!(baseUrlObj instanceof String template)) {
            return config;
        }
        Matcher matcher = BASE_URL_TEMPLATE.matcher(template.trim());
        if (!matcher.matches()) {
            return config;
        }
        if (sutEnvironment == null) {
            throw new IllegalStateException("SUT-aware baseUrl template used but no sutId was provided");
        }
        String endpointId = matcher.group(1);
        String suffix = matcher.group(2);
        SutEndpoint endpoint = sutEnvironment.endpoints().get(endpointId);
        if (endpoint == null) {
            throw new IllegalStateException("Unknown SUT endpoint '%s' for environment '%s'"
                .formatted(endpointId, sutEnvironment.id()));
        }
        String base = endpoint.baseUrl();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("SUT endpoint '%s' for environment '%s' has no baseUrl"
                .formatted(endpointId, sutEnvironment.id()));
        }
        String resolved = base.trim() + suffix;
        Map<String, Object> updated = new java.util.LinkedHashMap<>(config);
        updated.put("baseUrl", resolved);
        return Map.copyOf(updated);
    }
}
