package io.pockethive.artemis.config;

import java.util.Map;

/**
 * Responsibility: own Artemis configuration property and environment field mappings.
 * Must not: read environment values, reconstruct names or supply defaults.
 * Contract: RESP-ARTEMIS-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-artemis-configuration.
 */
public final class ArtemisEnvironmentKeys {
    public static final String CONNECTION_PREFIX = "pockethive.work.artemis";
    public static final String INPUT_PREFIX = "pockethive.inputs.artemis";
    public static final String OUTPUT_PREFIX = "pockethive.outputs.artemis";
    public static final String BROKER_URL_PROPERTY = CONNECTION_PREFIX + ".broker-url";
    public static final String USERNAME_PROPERTY = CONNECTION_PREFIX + ".username";
    public static final String PASSWORD_PROPERTY = CONNECTION_PREFIX + ".password";
    public static final String CALL_TIMEOUT_PROPERTY = CONNECTION_PREFIX + ".call-timeout-millis";
    public static final String NAMESPACE_PROPERTY = CONNECTION_PREFIX + ".namespace";
    public static final String BROKER_URL = "POCKETHIVE_WORK_ARTEMIS_BROKERURL";
    public static final String USERNAME = "POCKETHIVE_WORK_ARTEMIS_USERNAME";
    public static final String PASSWORD = "POCKETHIVE_WORK_ARTEMIS_PASSWORD";
    public static final String CALL_TIMEOUT = "POCKETHIVE_WORK_ARTEMIS_CALLTIMEOUTMILLIS";
    public static final String INPUT_QUEUE = "POCKETHIVE_INPUTS_ARTEMIS_QUEUE";
    public static final String INPUT_WINDOW = "POCKETHIVE_INPUTS_ARTEMIS_CONSUMERWINDOWBYTES";
    public static final String OUTPUT_ADDRESS = "POCKETHIVE_OUTPUTS_ARTEMIS_ADDRESS";
    public static final String OUTPUT_PERSISTENT = "POCKETHIVE_OUTPUTS_ARTEMIS_PERSISTENT";
    public static final String NAMESPACE = "POCKETHIVE_WORK_ARTEMIS_NAMESPACE";

    public static final Map<String, String> OWNED_PROPERTIES = Map.of(
        BROKER_URL_PROPERTY, BROKER_URL, USERNAME_PROPERTY, USERNAME, PASSWORD_PROPERTY, PASSWORD,
        CALL_TIMEOUT_PROPERTY, CALL_TIMEOUT, NAMESPACE_PROPERTY, NAMESPACE,
        INPUT_PREFIX + ".queue", INPUT_QUEUE, INPUT_PREFIX + ".consumer-window-bytes", INPUT_WINDOW,
        OUTPUT_PREFIX + ".address", OUTPUT_ADDRESS, OUTPUT_PREFIX + ".persistent", OUTPUT_PERSISTENT);

    private ArtemisEnvironmentKeys() { }
}
