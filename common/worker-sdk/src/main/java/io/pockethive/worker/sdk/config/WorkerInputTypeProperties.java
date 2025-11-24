package io.pockethive.worker.sdk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the worker's input type from configuration.
 * <p>
 * NFF: {@code type} must be configured; there is no fallback to {@link PocketHiveWorker}
 * annotation attributes. IO is driven exclusively from {@code pockethive.inputs.*}.
 */
@ConfigurationProperties(prefix = "pockethive.inputs")
public class WorkerInputTypeProperties {

    private WorkerInputType type;

    public WorkerInputType getType() {
        return type;
    }

    public void setType(WorkerInputType type) {
        this.type = type;
    }
}
