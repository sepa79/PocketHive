package io.pockethive.artemis.api;

import io.pockethive.artemis.config.ArtemisSettingValues;
import io.pockethive.work.config.WorkOutputSettings;
import io.pockethive.work.config.binding.WorkOutputConfig;

/**
 * Responsibility: own validated, resolved Artemis output settings and their read-only route projection.
 * Must not: resolve logical names, publish results or independently select an adapter.
 * Contract: RESP-ARTEMIS-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-artemis-configuration.
 */
public record ArtemisOutputSettings(String address, boolean persistent) implements WorkOutputSettings, WorkOutputConfig {
    public ArtemisOutputSettings {
        address = ArtemisSettingValues.requiredText(address, "address");
    }

    @Override public String outboundRoute() { return address; }
    @Override public String outboundGroup() { return address; }
}
