package io.pockethive.artemis.api;

import io.pockethive.artemis.config.ArtemisInputSettingsParser;
import io.pockethive.artemis.config.ArtemisOutputSettingsParser;
import io.pockethive.artemis.config.ArtemisInputMutationPolicy;
import io.pockethive.artemis.config.ArtemisOutputMutationPolicy;
import io.pockethive.work.config.*;

/**
 * Responsibility: expose Artemis configuration providers through the neutral parser/policy ports.
 * Must not: repeat field rules, choose an adapter or open infrastructure.
 * Contract: RESP-ARTEMIS-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-artemis-configuration.
 */
public final class ArtemisConfiguration {
    private ArtemisConfiguration() { }
    public static WorkInputSettingsParser inputParser() { return new ArtemisInputSettingsParser(); }
    public static WorkOutputSettingsParser outputParser() { return new ArtemisOutputSettingsParser(); }
    public static WorkInputMutationPolicy inputMutationPolicy() { return new ArtemisInputMutationPolicy(); }
    public static WorkOutputMutationPolicy outputMutationPolicy() { return new ArtemisOutputMutationPolicy(); }
}
