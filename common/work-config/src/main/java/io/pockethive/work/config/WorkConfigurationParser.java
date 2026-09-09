package io.pockethive.work.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Responsibility: canonically validate decoded Work settings and produce resolved immutable values.
 * Must not: read environment, render expressions, apply settings or accept deferred values at runtime.
 * Contract: RESP-WORK-REDIS-ROUTES — docs/architecture/runtime-responsibilities.md#resp-work-redis-routes.
 * Contract: RESP-WORK-REDIS-SOURCES — docs/architecture/runtime-responsibilities.md#resp-work-redis-sources.
 * Contract: RESP-WORK-REDIS-SELECTION — docs/architecture/runtime-responsibilities.md#resp-work-redis-selection.
 * Contract: RESP-WORK-REDIS-TARGETS — docs/architecture/runtime-responsibilities.md#resp-work-redis-targets.
 * Contract: RESP-WORK-REDIS-WRITE-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-redis-write-settings.
 * Complete IO/candidate parsing remains B02 work.
 * Contract: RESP-REDIS-CONNECTION-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-redis-connection-settings.
 */
public final class WorkConfigurationParser {
    public static final Set<String> REDIS_CONNECTION_FIELDS = Set.of("host", "port", "username", "password", "ssl");
    private static final Set<String> ROUTE_FIELDS = Set.of("match", "header", "headerMatch", "list");
    private static final Set<String> SOURCE_FIELDS = Set.of("listName", "weight");

    public RedisConnectionSettings parseRedisConnection(Map<?, ?> values, String path) {
        return parseRedisConnection(values.get("host"), values.get("port"), values.get("username"),
            values.get("password"), values.get("ssl"), path);
    }

    public RedisConnectionValidation validateRedisConnection(Object values, String path, WorkConfigurationMode mode) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");
        var problems = new ArrayList<WorkConfigurationProblem>();
        var deferred = new ArrayList<String>();
        if (symbolic(values, path, mode, problems, deferred)) {
            return new RedisConnectionValidation(null, problems, deferred);
        }
        if (!(values instanceof Map<?, ?> fields)) {
            return new RedisConnectionValidation(null,
                List.of(new WorkConfigurationProblem(path, "Redis settings must be an object.")), List.of());
        }
        return validateRedisConnection(fields.get("host"), fields.get("port"), fields.get("username"),
            fields.get("password"), fields.get("ssl"), path, mode);
    }

    public RedisConnectionSettings mergeRedisConnection(RedisConnectionSettings base, Map<?, ?> patch, String path) {
        return parseRedisConnection(
            patch.containsKey("host") ? patch.get("host") : base.host(),
            patch.containsKey("port") ? patch.get("port") : base.port(),
            patch.containsKey("username") ? patch.get("username") : base.username(),
            patch.containsKey("password") ? patch.get("password") : base.password(),
            patch.containsKey("ssl") ? patch.get("ssl") : base.ssl(), path);
    }

    public RedisConnectionSettings parseRedisConnection(Object host, Object port, Object username, Object password,
                                                         Object ssl, String path) {
        var result = validateRedisConnection(host, port, username, password, ssl, path, WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) {
            throw new WorkConfigurationException(result.problems());
        }
        return result.settings();
    }

    public RedisConnectionValidation validateRedisConnection(Object host, Object port, Object username, Object password,
                                                              Object ssl, String path, WorkConfigurationMode mode) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");
        var problems = new ArrayList<WorkConfigurationProblem>();
        var deferred = new ArrayList<String>();
        boolean hostSymbolic = symbolic(host, path + ".host", mode, problems, deferred);
        String parsedHost = hostSymbolic ? null : connectionText(host, path + ".host", problems);
        parsedHost = parsedHost == null ? null : parsedHost.trim();
        if (!hostSymbolic && (parsedHost == null || parsedHost.isEmpty()) && (host == null || host instanceof String)) {
            problems.add(new WorkConfigurationProblem(path + ".host", "Host must be configured and nonblank."));
        }
        Integer parsedPort = symbolic(port, path + ".port", mode, problems, deferred) ? null
            : integerAtLeast(port, 1, path + ".port", problems);
        if (parsedPort != null && parsedPort > 65_535) {
            problems.add(new WorkConfigurationProblem(path + ".port", "Port must be between 1 and 65535."));
        }
        String parsedUsername = symbolic(username, path + ".username", mode, problems, deferred) ? null
            : connectionText(username, path + ".username", problems);
        parsedUsername = parsedUsername == null || parsedUsername.trim().isEmpty() ? null : parsedUsername.trim();
        boolean passwordSymbolic = symbolic(password, path + ".password", mode, problems, deferred);
        String parsedPassword = passwordSymbolic ? null : connectionText(password, path + ".password", problems);
        if (parsedUsername != null && password == null) {
            problems.add(new WorkConfigurationProblem(path + ".password", "Password must be explicit when username is configured."));
        }
        Boolean parsedSsl = symbolic(ssl, path + ".ssl", mode, problems, deferred) ? null
            : connectionBoolean(ssl, path + ".ssl", problems);
        var settings = problems.isEmpty() && deferred.isEmpty()
            ? new RedisConnectionSettings(parsedHost, parsedPort, parsedUsername, parsedPassword, parsedSsl) : null;
        return new RedisConnectionValidation(settings, problems, deferred);
    }

    private String connectionText(Object value, String path, List<WorkConfigurationProblem> problems) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        problems.add(new WorkConfigurationProblem(path, "Must be text."));
        return null;
    }

    private Boolean connectionBoolean(Object value, String path, List<WorkConfigurationProblem> problems) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text.trim())) return true;
            if ("false".equalsIgnoreCase(text.trim())) return false;
        }
        problems.add(new WorkConfigurationProblem(path, "Must be true or false."));
        return null;
    }

    public RedisWriteSettings parseRedisWriteSettings(Object sourceStep, Object pushDirection, Object maxLen, String path) {
        var result = validateRedisWriteSettings(sourceStep, pushDirection, maxLen, path, WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) {
            throw new WorkConfigurationException(result.problems());
        }
        return result.settings();
    }

    public RedisWriteSettingsValidation validateRedisWriteSettings(Object sourceStep, Object pushDirection,
                                                                    Object maxLen, String path, WorkConfigurationMode mode) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");
        var problems = new ArrayList<WorkConfigurationProblem>();
        var deferred = new ArrayList<String>();
        var source = symbolic(sourceStep, path + ".sourceStep", mode, problems, deferred) ? null
            : enumValue(sourceStep, RedisPayloadSource.class, path + ".sourceStep", problems);
        var direction = symbolic(pushDirection, path + ".pushDirection", mode, problems, deferred) ? null
            : enumValue(pushDirection, RedisPushDirection.class, path + ".pushDirection", problems);
        Integer length = symbolic(maxLen, path + ".maxLen", mode, problems, deferred) ? null
            : integerAtLeast(maxLen, RedisWriteSettings.MIN_MAX_LEN, path + ".maxLen", problems);
        var settings = problems.isEmpty() && deferred.isEmpty() ? new RedisWriteSettings(source, direction, length) : null;
        return new RedisWriteSettingsValidation(settings, problems, deferred);
    }

    private <E extends Enum<E>> E enumValue(Object value, Class<E> type, String path, List<WorkConfigurationProblem> problems) {
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        if (value instanceof String text) {
            try {
                return Enum.valueOf(type, text.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Invalid text receives the same field problem as missing/wrong-type input.
            }
        }
        problems.add(new WorkConfigurationProblem(path, "Must be one of " + Arrays.toString(type.getEnumConstants()) + "."));
        return null;
    }

    private Integer integerAtLeast(Object value, int minimum, String path, List<WorkConfigurationProblem> problems) {
        Integer integer = null;
        try {
            if (value instanceof Number number) {
                integer = new BigDecimal(number.toString()).intValueExact();
            } else if (value instanceof String text) {
                integer = Integer.parseInt(text.trim());
            }
        } catch (NumberFormatException | ArithmeticException ignored) {
            // Report invalid, fractional and overflowing input through the canonical field path.
        }
        if (integer == null) {
            problems.add(new WorkConfigurationProblem(path, "Must be a finite 32-bit integer."));
        } else if (integer < minimum) {
            problems.add(new WorkConfigurationProblem(path, "Must be " + minimum + " or greater."));
        }
        return integer;
    }

    public RedisOutputTargetsValidation parseRedisOutputTargets(Object declarations, Object defaultList,
                                                                 Object targetListTemplate, String path) {
        var result = validateRedisOutputTargets(declarations, defaultList, targetListTemplate, path,
            WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) {
            throw new WorkConfigurationException(result.problems());
        }
        return result;
    }

    public RedisOutputTargetsValidation validateRedisOutputTargets(Object declarations, Object defaultList,
                                                                    Object targetListTemplate, String path,
                                                                    WorkConfigurationMode mode) {
        Objects.requireNonNull(path, "path");
        var routes = validateRedisRoutes(declarations, path + ".routes", mode);
        var problems = new ArrayList<>(routes.problems());
        var deferred = new ArrayList<>(routes.deferredPaths());
        String list = targetText(defaultList, path + ".defaultList", problems);
        String template = targetText(targetListTemplate, path + ".targetListTemplate", problems);
        symbolic(list, path + ".defaultList", mode, problems, deferred);
        // targetListTemplate belongs to per-message rendering, not bootstrap rendering.
        if (routes.isEmpty() && list == null && template == null && problems.isEmpty()) {
            problems.add(new WorkConfigurationProblem(path,
                "Redis output requires at least one target: routes, targetListTemplate or defaultList."));
        }
        return new RedisOutputTargetsValidation(routes.routes(), list, template, problems, deferred);
    }

    private String targetText(Object value, String path, List<WorkConfigurationProblem> problems) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            problems.add(new WorkConfigurationProblem(path, "Redis output target must be text."));
            return null;
        }
        String normalized = text.trim();
        return normalized.isBlank() ? null : normalized;
    }

    public RedisDatasetSelectionValidation parseRedisDatasetSelection(Object listName, Object sources, String path) {
        var result = validateRedisDatasetSelection(listName, sources, path, WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) {
            throw new WorkConfigurationException(result.problems());
        }
        return result;
    }

    public RedisDatasetSelectionValidation validateRedisDatasetSelection(Object listName, Object declarations,
                                                                         String path, WorkConfigurationMode mode) {
        Objects.requireNonNull(path, "path");
        var sources = validateRedisSources(declarations, path + ".sources", mode);
        var problems = new ArrayList<>(sources.problems());
        var deferred = new ArrayList<>(sources.deferredPaths());
        String name = null;
        boolean symbolicName = symbolic(listName, path + ".listName", mode, problems, deferred);
        if (!symbolicName) {
            try {
                name = RedisDatasetSource.decodeOptionalName(listName);
            } catch (IllegalArgumentException error) {
                problems.add(new WorkConfigurationProblem(path + ".listName", error.getMessage()));
            }
        }
        // List cardinality is known even when an entry contains a deferred name or weight.
        if (!symbolicName && declarations instanceof List<?> entries && problems.isEmpty()
            && (name != null) == !entries.isEmpty()) {
            problems.add(new WorkConfigurationProblem(path,
                "Redis dataset input requires exactly one source mode: listName or sources."));
        }
        var selected = name != null ? RedisDatasetSourceMode.SINGLE : RedisDatasetSourceMode.MULTIPLE;
        return new RedisDatasetSelectionValidation(selected, name, sources.sources(), problems, deferred);
    }

    public List<RedisDatasetSource> parseRedisSources(Object declarations, String path) {
        var result = validateRedisSources(declarations, path, WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) {
            throw new WorkConfigurationException(result.problems());
        }
        return result.sources();
    }

    public RedisSourcesValidation validateRedisSources(Object declarations, String path, WorkConfigurationMode mode) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");
        List<WorkConfigurationProblem> problems = new ArrayList<>();
        List<String> deferred = new ArrayList<>();
        List<RedisDatasetSource> sources = new ArrayList<>();
        if (symbolic(declarations, path, mode, problems, deferred)) {
            return new RedisSourcesValidation(sources, problems, deferred);
        }
        if (!(declarations instanceof List<?> entries)) {
            problems.add(new WorkConfigurationProblem(path, "Redis dataset sources must be a list."));
            return new RedisSourcesValidation(sources, problems, deferred);
        }
        Set<String> names = new LinkedHashSet<>();
        for (int index = 0; index < entries.size(); index++) {
            String entryPath = path + "[" + index + "]";
            int errorsBefore = problems.size();
            int deferredBefore = deferred.size();
            Object name;
            Object weight;
            Object entry = entries.get(index);
            if (entry instanceof RedisDatasetSource source) {
                name = source.getListName();
                weight = source.getWeight();
            } else if (entry instanceof Map<?, ?> fields) {
                for (Object key : fields.keySet()) {
                    if (!(key instanceof String field) || !SOURCE_FIELDS.contains(field)) {
                        problems.add(new WorkConfigurationProblem(entryPath + "." + key, "Unknown Redis dataset source field."));
                    }
                }
                name = fields.get("listName");
                weight = fields.get("weight");
            } else {
                problems.add(new WorkConfigurationProblem(entryPath, "Redis dataset source entry must be an object."));
                continue;
            }
            String resolvedName = null;
            double resolvedWeight = 0.0;
            if (!symbolic(name, entryPath + ".listName", mode, problems, deferred)) {
                try {
                    resolvedName = RedisDatasetSource.decodeName(name);
                    if (!names.add(resolvedName)) {
                        problems.add(new WorkConfigurationProblem(entryPath + ".listName",
                            "Redis dataset sources must not contain duplicate listName '" + resolvedName + "'."));
                    }
                } catch (IllegalArgumentException error) {
                    problems.add(new WorkConfigurationProblem(entryPath + ".listName", error.getMessage()));
                }
            }
            if (!symbolic(weight, entryPath + ".weight", mode, problems, deferred)) {
                try {
                    resolvedWeight = RedisDatasetSource.decodeWeight(weight);
                } catch (IllegalArgumentException error) {
                    problems.add(new WorkConfigurationProblem(entryPath + ".weight", error.getMessage()));
                }
            }
            if (errorsBefore == problems.size() && deferredBefore == deferred.size()) {
                sources.add(new RedisDatasetSource(resolvedName, resolvedWeight));
            }
        }
        return new RedisSourcesValidation(sources, problems, deferred);
    }

    public List<RedisRoute> parseRedisRoutes(Object declarations, String path) {
        RedisRoutesValidation result = validateRedisRoutes(declarations, path, WorkConfigurationMode.RESOLVED);
        if (!result.problems().isEmpty()) {
            throw new WorkConfigurationException(result.problems());
        }
        return result.routes();
    }

    public RedisRoutesValidation validateRedisRoutes(Object declarations, String path, WorkConfigurationMode mode) {
        List<WorkConfigurationProblem> problems = new ArrayList<>();
        List<String> deferred = new ArrayList<>();
        List<RedisRoute> routes = redisRoutes(declarations, path, mode, problems, deferred);
        return new RedisRoutesValidation(routes, problems, deferred);
    }

    private List<RedisRoute> redisRoutes(Object declarations, String path, WorkConfigurationMode mode,
                                        List<WorkConfigurationProblem> problems, List<String> deferred) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");
        if (declarations == null) {
            return List.of();
        }
        if (symbolic(declarations, path, mode, problems, deferred)) {
            return List.of();
        }
        if (!(declarations instanceof List<?> entries)) {
            problems.add(new WorkConfigurationProblem(path, "Redis output routes must be a list."));
            return List.of();
        }
        List<RedisRoute> routes = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            String entryPath = path + "[" + index + "]";
            int errorsBefore = problems.size();
            int deferredBefore = deferred.size();
            if (entries.get(index) instanceof RedisRoute route) {
                routes.add(route);
                continue;
            }
            RedisRouteDefinition definition = definition(entries.get(index), entryPath, problems);
            if (definition == null) {
                continue;
            }
            String match = text(definition.match(), entryPath + ".match", problems);
            String header = text(definition.header(), entryPath + ".header", problems);
            String headerMatch = text(definition.headerMatch(), entryPath + ".headerMatch", problems);
            String list = text(definition.list(), entryPath + ".list", problems);
            boolean symbolicHeader = symbolic(header, entryPath + ".header", mode, problems, deferred);
            if (list == null) {
                problems.add(new WorkConfigurationProblem(entryPath + ".list", "Redis output route list must not be blank."));
            }
            if (match == null && header == null) {
                problems.add(new WorkConfigurationProblem(entryPath, "Redis output route requires match and/or header."));
            }
            if (header != null && headerMatch == null && (!symbolicHeader || match == null)) {
                problems.add(new WorkConfigurationProblem(entryPath + ".headerMatch",
                    "Redis output route headerMatch must be configured when header is set."));
            }
            symbolic(list, entryPath + ".list", mode, problems, deferred);
            Pattern payloadPattern = pattern(match, entryPath + ".match", mode, problems, deferred);
            Pattern headerPattern = pattern(headerMatch, entryPath + ".headerMatch", mode, problems, deferred);
            if (errorsBefore == problems.size() && deferredBefore == deferred.size()) {
                routes.add(new RedisRoute(payloadPattern, header, headerPattern, list));
            }
        }
        return routes;
    }

    private RedisRouteDefinition definition(Object value, String path, List<WorkConfigurationProblem> problems) {
        if (value instanceof RedisRouteDefinition definition) {
            return definition;
        }
        if (!(value instanceof Map<?, ?> fields)) {
            problems.add(new WorkConfigurationProblem(path, "Redis output route entry must be an object."));
            return null;
        }
        for (Object key : fields.keySet()) {
            if (!(key instanceof String field) || !ROUTE_FIELDS.contains(field)) {
                problems.add(new WorkConfigurationProblem(path + "." + key, "Unknown Redis route field."));
            }
        }
        return new RedisRouteDefinition(fields.get("match"), fields.get("header"), fields.get("headerMatch"), fields.get("list"));
    }

    private String text(Object value, String path, List<WorkConfigurationProblem> problems) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text.isBlank() ? null : text;
        }
        problems.add(new WorkConfigurationProblem(path, "Redis route field must be a string."));
        return null;
    }

    private Pattern pattern(String value, String path, WorkConfigurationMode mode,
                            List<WorkConfigurationProblem> problems, List<String> deferred) {
        if (value == null || symbolic(value, path, mode, problems, deferred)) {
            return null;
        }
        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            problems.add(new WorkConfigurationProblem(path, "Redis output route regex is invalid: " + e.getDescription()));
            return null;
        }
    }

    private boolean symbolic(Object value, String path, WorkConfigurationMode mode,
                             List<WorkConfigurationProblem> problems, List<String> deferred) {
        if (!(value instanceof String text)
            || !((text.contains("{{") && text.contains("}}")) || (text.contains("{%") && text.contains("%}")))) {
            return false;
        }
        if (mode == WorkConfigurationMode.AUTHORING) {
            deferred.add(path);
        } else {
            problems.add(new WorkConfigurationProblem(path, "Configuration expression must be rendered before runtime parsing."));
        }
        return true;
    }

}
