package io.pockethive.artemis.api;

import io.pockethive.work.config.WorkIoType;

/**
 * Responsibility: identify the Artemis Work adapter and its settings block.
 * Must not: select an implementation, parse configuration or open infrastructure.
 * Contract: RESP-ARTEMIS-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-artemis-configuration.
 */
public enum ArtemisWorkIoType implements WorkIoType {
    ARTEMIS;

    @Override public boolean supportsDelayedDelivery() { return true; }

    @Override public String settingsKey() { return "artemis"; }
}
