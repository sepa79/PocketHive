package io.pockethive.rabbit.config;
import io.pockethive.rabbit.api.RabbitOutputSettings;
import io.pockethive.rabbit.api.RabbitOutputTuning;


import io.pockethive.work.config.WorkConfigurationExpressions;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkOutputSettings;
import io.pockethive.work.config.WorkConfigurationProblem;
import io.pockethive.work.config.WorkOutputSettingsParseResult;
import io.pockethive.work.config.WorkOutputSettingsParser;
import io.pockethive.work.config.WorkerOutputType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: validate Rabbit output settings and provide them through the neutral Work output parser port.
 * Must not: resolve connections, publish messages or retain accepted Work configuration.
 * Contract: RESP-WORK-RABBIT-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-settings.
 */
public final class RabbitOutputSettingsParser implements WorkOutputSettingsParser {
    public static final String EXCHANGE_FIELD = "exchange";
    public static final String ROUTING_KEY_FIELD = "routingKey";
    public static final String PERSISTENT_FIELD = "persistent";
    public static final String PUBLISHER_CONFIRMS_FIELD = "publisherConfirms";


    private static final Set<String> FIELDS = Set.of(
        EXCHANGE_FIELD,
        ROUTING_KEY_FIELD,
        PERSISTENT_FIELD,
        PUBLISHER_CONFIRMS_FIELD
    );

    @Override
    public WorkerOutputType type() {
        return WorkerOutputType.RABBITMQ;
    }

    @Override
    public WorkOutputSettingsParseResult validate(Map<?, ?> settings, String path, WorkConfigurationMode mode) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");

        List<WorkConfigurationProblem> problems = new ArrayList<>();
        List<String> deferredPaths = new ArrayList<>();
        rejectUnknownFields(settings, path, problems);
        boolean authoring = mode == WorkConfigurationMode.AUTHORING;

        String exchange = authoring ? null : requiredText(
            settings.get(EXCHANGE_FIELD), path + "." + EXCHANGE_FIELD, mode, problems, deferredPaths
        );
        if (authoring && settings.containsKey(EXCHANGE_FIELD)) {
            problems.add(new WorkConfigurationProblem(path + "." + EXCHANGE_FIELD,
                "Physical Rabbit destinations are owned by topology and cannot be authored."));
        }
        String routingKey = authoring ? null : requiredText(
            settings.get(ROUTING_KEY_FIELD), path + "." + ROUTING_KEY_FIELD, mode, problems, deferredPaths
        );
        if (authoring && settings.containsKey(ROUTING_KEY_FIELD)) {
            problems.add(new WorkConfigurationProblem(path + "." + ROUTING_KEY_FIELD,
                "Physical Rabbit destinations are owned by topology and cannot be authored."));
        }
        Boolean persistent = booleanValue(
            settings.containsKey(PERSISTENT_FIELD) ? settings.get(PERSISTENT_FIELD) : RabbitOutputSettings.DEFAULT_PERSISTENT,
            path + "." + PERSISTENT_FIELD,
            mode,
            problems,
            deferredPaths
        );
        Boolean publisherConfirms = booleanValue(
            settings.containsKey(PUBLISHER_CONFIRMS_FIELD)
                ? settings.get(PUBLISHER_CONFIRMS_FIELD) : RabbitOutputSettings.DEFAULT_PUBLISHER_CONFIRMS,
            path + "." + PUBLISHER_CONFIRMS_FIELD,
            mode,
            problems,
            deferredPaths
        );

        WorkOutputSettings resolved = problems.isEmpty() && deferredPaths.isEmpty()
            ? authoring ? new RabbitOutputTuning(persistent, publisherConfirms) : new RabbitOutputSettings(exchange, routingKey, persistent, publisherConfirms)
            : null;
        return new WorkOutputSettingsParseResult(resolved, problems, deferredPaths);
    }

    public static Map<String, Object> configuration(RabbitOutputSettings settings) {
        return Map.of(EXCHANGE_FIELD, settings.exchange(), ROUTING_KEY_FIELD, settings.routingKey(),
            PERSISTENT_FIELD, settings.persistent(), PUBLISHER_CONFIRMS_FIELD, settings.publisherConfirms());
    }

    private void rejectUnknownFields(Map<?, ?> settings, String path, List<WorkConfigurationProblem> problems) {
        for (Object key : settings.keySet()) {
            if (!(key instanceof String field) || !FIELDS.contains(field)) {
                problems.add(new WorkConfigurationProblem(path + "." + key, "Unsupported Rabbit output setting."));
            }
        }
    }

    private String requiredText(Object value, String path, WorkConfigurationMode mode,
                                List<WorkConfigurationProblem> problems, List<String> deferredPaths) {
        if (WorkConfigurationExpressions.symbolic(value, path, mode, problems, deferredPaths)) return null;
        try {
            return RabbitSettingValues.requiredText(value);
        } catch (IllegalArgumentException invalid) {
            problems.add(new WorkConfigurationProblem(path, invalid.getMessage()));
            return null;
        }
    }

    private Boolean booleanValue(Object value, String path, WorkConfigurationMode mode,
                                 List<WorkConfigurationProblem> problems, List<String> deferredPaths) {
        if (WorkConfigurationExpressions.symbolic(value, path, mode, problems, deferredPaths)) return null;
        try {
            return RabbitSettingValues.booleanValue(value);
        } catch (IllegalArgumentException invalid) {
            problems.add(new WorkConfigurationProblem(path, invalid.getMessage()));
            return null;
        }
    }
}
