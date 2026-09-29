package io.pockethive.artemis.config;

import static io.pockethive.artemis.config.ArtemisConfigurationFields.*;

import io.pockethive.artemis.api.ArtemisInputSettings;
import io.pockethive.artemis.api.ArtemisInputTuning;
import io.pockethive.artemis.api.ArtemisWorkIoType;
import io.pockethive.work.config.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: decode Artemis input maps through the canonical scalar rules and typed settings.
 * Must not: resolve physical queues, open consumers or accept authored destinations.
 * Contract: RESP-ARTEMIS-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-artemis-configuration.
 */
public final class ArtemisInputSettingsParser implements WorkInputSettingsParser {
    @Override public ArtemisWorkIoType type() { return ArtemisWorkIoType.ARTEMIS; }

    @Override
    public WorkInputSettingsParseResult validate(Map<?, ?> settings, String path, WorkConfigurationMode mode) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");
        var problems = new ArrayList<WorkConfigurationProblem>();
        var deferred = new ArrayList<String>();
        boolean authoring = mode == WorkConfigurationMode.AUTHORING;
        ArtemisSettingsParsing.fields(settings, authoring ? Set.of(CONSUMER_WINDOW_BYTES)
            : Set.of(QUEUE, CONSUMER_WINDOW_BYTES), path, problems);
        Integer window = ArtemisSettingsParsing.value(settings.get(CONSUMER_WINDOW_BYTES),
            path + "." + CONSUMER_WINDOW_BYTES, mode, problems, deferred,
            raw -> ArtemisSettingValues.nonnegative(ArtemisSettingValues.integer(raw, CONSUMER_WINDOW_BYTES), CONSUMER_WINDOW_BYTES));
        String queue = authoring ? null : ArtemisSettingsParsing.value(settings.get(QUEUE),
            path + "." + QUEUE, mode, problems, deferred, raw -> ArtemisSettingValues.text(raw, QUEUE));
        WorkInputSettings parsed = problems.isEmpty() && deferred.isEmpty()
            ? authoring ? new ArtemisInputTuning(window) : new ArtemisInputSettings(queue, window) : null;
        return new WorkInputSettingsParseResult(parsed, problems, deferred);
    }

    public static Map<String, Object> configuration(ArtemisInputSettings settings) {
        return Map.of(QUEUE, settings.queue(), CONSUMER_WINDOW_BYTES, settings.consumerWindowBytes());
    }
}
