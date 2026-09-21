package io.pockethive.rabbit.config;
import io.pockethive.rabbit.api.RabbitInputSettings;
import io.pockethive.rabbit.api.RabbitInputTuning;


import io.pockethive.work.config.WorkConfigurationExpressions;
import io.pockethive.work.config.WorkConfigurationMode;
import io.pockethive.work.config.WorkInputSettings;
import io.pockethive.work.config.WorkConfigurationProblem;
import io.pockethive.work.config.WorkInputSettingsParseResult;
import io.pockethive.work.config.WorkInputSettingsParser;
import io.pockethive.work.config.WorkerInputType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: validate Rabbit input settings and provide them through the neutral Work input parser port.
 * Must not: resolve connections, construct consumers or retain accepted Work configuration.
 * Contract: RESP-WORK-RABBIT-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-settings.
 */
public final class RabbitInputSettingsParser implements WorkInputSettingsParser {
    public static final String QUEUE_FIELD = "queue";
    public static final String PREFETCH_FIELD = "prefetch";
    public static final String CONCURRENT_CONSUMERS_FIELD = "concurrentConsumers";
    public static final String EXCLUSIVE_FIELD = "exclusive";


    private static final Set<String> FIELDS = Set.of(
        QUEUE_FIELD,
        PREFETCH_FIELD,
        CONCURRENT_CONSUMERS_FIELD,
        EXCLUSIVE_FIELD
    );

    @Override
    public WorkerInputType type() {
        return WorkerInputType.RABBITMQ;
    }

    @Override
    public WorkInputSettingsParseResult validate(Map<?, ?> settings, String path, WorkConfigurationMode mode) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");

        List<WorkConfigurationProblem> problems = new ArrayList<>();
        List<String> deferredPaths = new ArrayList<>();
        rejectUnknownFields(settings, path, problems);
        boolean authoring = mode == WorkConfigurationMode.AUTHORING;

        String queue = authoring ? null : requiredText(settings.get(QUEUE_FIELD), path + "." + QUEUE_FIELD, mode, problems, deferredPaths);
        if (authoring && settings.containsKey(QUEUE_FIELD)) {
            problems.add(new WorkConfigurationProblem(path + "." + QUEUE_FIELD,
                "Physical Rabbit destinations are owned by topology and cannot be authored."));
        }
        Integer prefetch = positiveInteger(
            settings.containsKey(PREFETCH_FIELD) ? settings.get(PREFETCH_FIELD) : RabbitInputSettings.DEFAULT_PREFETCH,
            path + "." + PREFETCH_FIELD,
            mode,
            problems,
            deferredPaths
        );
        Integer concurrentConsumers = positiveInteger(
            settings.containsKey(CONCURRENT_CONSUMERS_FIELD)
                ? settings.get(CONCURRENT_CONSUMERS_FIELD) : RabbitInputSettings.DEFAULT_CONCURRENT_CONSUMERS,
            path + "." + CONCURRENT_CONSUMERS_FIELD,
            mode,
            problems,
            deferredPaths
        );
        Boolean exclusive = booleanValue(
            settings.containsKey(EXCLUSIVE_FIELD) ? settings.get(EXCLUSIVE_FIELD) : RabbitInputSettings.DEFAULT_EXCLUSIVE,
            path + "." + EXCLUSIVE_FIELD,
            mode,
            problems,
            deferredPaths
        );

        if (concurrentConsumers != null && exclusive != null) {
            try {
                RabbitSettingValues.consumerPolicy(concurrentConsumers, exclusive);
            } catch (IllegalArgumentException invalid) {
                problems.add(new WorkConfigurationProblem(path + "." + CONCURRENT_CONSUMERS_FIELD, invalid.getMessage()));
            }
        }
        WorkInputSettings resolved = problems.isEmpty() && deferredPaths.isEmpty()
            ? authoring ? new RabbitInputTuning(prefetch, concurrentConsumers, exclusive) : new RabbitInputSettings(queue, prefetch, concurrentConsumers, exclusive)
            : null;
        return new WorkInputSettingsParseResult(resolved, problems, deferredPaths);
    }

    public static Map<String, Object> configuration(RabbitInputSettings settings) {
        return Map.of(QUEUE_FIELD, settings.queue(), PREFETCH_FIELD, settings.prefetch(),
            CONCURRENT_CONSUMERS_FIELD, settings.concurrentConsumers(), EXCLUSIVE_FIELD, settings.exclusive());
    }

    private void rejectUnknownFields(Map<?, ?> settings, String path, List<WorkConfigurationProblem> problems) {
        for (Object key : settings.keySet()) {
            if (!(key instanceof String field) || !FIELDS.contains(field)) {
                problems.add(new WorkConfigurationProblem(path + "." + key, "Unsupported Rabbit input setting."));
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

    private Integer positiveInteger(Object value, String path, WorkConfigurationMode mode,
                                    List<WorkConfigurationProblem> problems, List<String> deferredPaths) {
        if (WorkConfigurationExpressions.symbolic(value, path, mode, problems, deferredPaths)) return null;
        try {
            return RabbitSettingValues.positiveInteger(value);
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
