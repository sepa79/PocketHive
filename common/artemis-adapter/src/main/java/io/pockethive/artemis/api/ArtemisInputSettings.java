package io.pockethive.artemis.api;

import io.pockethive.artemis.config.ArtemisSettingValues;
import io.pockethive.work.config.WorkInputSettings;
import io.pockethive.work.config.binding.WorkInputConfig;

/**
 * Responsibility: own validated, resolved Artemis input settings and their read-only route projection.
 * Must not: resolve logical names, open consumers or independently select an adapter.
 * Contract: RESP-ARTEMIS-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-artemis-configuration.
 */
public record ArtemisInputSettings(String queue, Integer consumerWindowBytes) implements WorkInputSettings, WorkInputConfig {
    public ArtemisInputSettings {
        queue = ArtemisSettingValues.requiredText(queue, "queue");
        consumerWindowBytes = ArtemisSettingValues.nonnegative(consumerWindowBytes, "consumerWindowBytes");
    }

    @Override public String inboundRoute() { return queue; }
}
