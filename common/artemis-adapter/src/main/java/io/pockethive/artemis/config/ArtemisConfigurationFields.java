package io.pockethive.artemis.config;

/**
 * Responsibility: own Artemis input/output field names used by parsing and projections.
 * Must not: validate values, supply defaults or resolve addresses.
 * Contract: RESP-ARTEMIS-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-artemis-configuration.
 */
public final class ArtemisConfigurationFields {
    public static final String QUEUE = "queue";
    public static final String CONSUMER_WINDOW_BYTES = "consumerWindowBytes";
    public static final String ADDRESS = "address";
    public static final String PERSISTENT = "persistent";
    private ArtemisConfigurationFields() { }
}
