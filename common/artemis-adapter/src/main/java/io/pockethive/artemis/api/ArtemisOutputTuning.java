package io.pockethive.artemis.api;

import io.pockethive.artemis.config.ArtemisSettingValues;
import io.pockethive.work.config.WorkOutputSettings;

/**
 * Responsibility: retain validated authored Artemis output tuning before topology resolution.
 * Must not: contain physical destinations or select connections.
 * Contract: RESP-ARTEMIS-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-artemis-configuration.
 */
public record ArtemisOutputTuning(Boolean persistent) implements WorkOutputSettings {
    public ArtemisOutputTuning {
        persistent = ArtemisSettingValues.requiredBoolean(persistent, "persistent");
    }
}
