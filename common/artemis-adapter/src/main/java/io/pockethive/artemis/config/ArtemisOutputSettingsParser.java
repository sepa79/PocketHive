package io.pockethive.artemis.config;

import static io.pockethive.artemis.config.ArtemisConfigurationFields.*;

import io.pockethive.artemis.api.ArtemisOutputSettings;
import io.pockethive.artemis.api.ArtemisOutputTuning;
import io.pockethive.artemis.api.ArtemisWorkIoType;
import io.pockethive.work.config.*;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: decode Artemis output maps through the canonical scalar rules and typed settings.
 * Must not: resolve physical addresses, publish or accept authored destinations.
 * Contract: RESP-ARTEMIS-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-artemis-configuration.
 */
public final class ArtemisOutputSettingsParser implements WorkOutputSettingsParser {
    @Override public ArtemisWorkIoType type() { return ArtemisWorkIoType.ARTEMIS; }

    @Override
    public WorkOutputSettingsParseResult validate(Map<?, ?> settings, String path, WorkConfigurationMode mode) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(mode, "mode");
        var problems = new ArrayList<WorkConfigurationProblem>();
        var deferred = new ArrayList<String>();
        boolean authoring = mode == WorkConfigurationMode.AUTHORING;
        ArtemisSettingsParsing.fields(settings, authoring ? Set.of(PERSISTENT)
            : Set.of(ADDRESS, PERSISTENT), path, problems);
        Boolean persistent = ArtemisSettingsParsing.value(settings.get(PERSISTENT), path + "." + PERSISTENT,
            mode, problems, deferred, raw -> ArtemisSettingValues.booleanValue(raw, PERSISTENT));
        String address = authoring ? null : ArtemisSettingsParsing.value(settings.get(ADDRESS),
            path + "." + ADDRESS, mode, problems, deferred, raw -> ArtemisSettingValues.text(raw, ADDRESS));
        WorkOutputSettings parsed = problems.isEmpty() && deferred.isEmpty()
            ? authoring ? new ArtemisOutputTuning(persistent) : new ArtemisOutputSettings(address, persistent) : null;
        return new WorkOutputSettingsParseResult(parsed, problems, deferred);
    }

    public static Map<String, Object> configuration(ArtemisOutputSettings settings) {
        return Map.of(ADDRESS, settings.address(), PERSISTENT, settings.persistent());
    }
}
