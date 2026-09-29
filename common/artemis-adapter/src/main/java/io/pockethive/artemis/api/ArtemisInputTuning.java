package io.pockethive.artemis.api;

import io.pockethive.artemis.config.ArtemisSettingValues;
import io.pockethive.work.config.WorkInputSettings;

/**
 * Responsibility: retain validated authored Artemis input tuning before topology resolution.
 * Must not: contain physical destinations or select connections.
 * Contract: RESP-ARTEMIS-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-artemis-configuration.
 */
public record ArtemisInputTuning(Integer consumerWindowBytes) implements WorkInputSettings {
    public ArtemisInputTuning {
        consumerWindowBytes = ArtemisSettingValues.nonnegative(consumerWindowBytes, "consumerWindowBytes");
    }
}
