package io.pockethive.worker.sdk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the worker's output type from configuration.
 * <p>
 * NFF: {@code type} must be configured; there is no fallback to {@link PocketHiveWorker}
 * annotation attributes. IO is driven exclusively from {@code pockethive.outputs.*}.
 */
@ConfigurationProperties(prefix = "pockethive.outputs")
public class WorkerOutputTypeProperties {

    private WorkerOutputType type;

    public WorkerOutputType getType() {
        return type;
    }

    public void setType(WorkerOutputType type) {
        this.type = type;
    }
}
