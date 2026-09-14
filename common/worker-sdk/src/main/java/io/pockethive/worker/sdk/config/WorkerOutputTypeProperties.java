package io.pockethive.worker.sdk.config;


import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Responsibility: retain the explicit startup output selection text for the IO catalog.
 * Must not: infer adapter selection or parse adapter settings.
 * Contract: RESP-WORK-IO-CONFIG — docs/architecture/runtime-responsibilities.md#resp-work-io-config.
 */
@ConfigurationProperties(prefix = "pockethive.outputs")
public class WorkerOutputTypeProperties {

    private String type;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
