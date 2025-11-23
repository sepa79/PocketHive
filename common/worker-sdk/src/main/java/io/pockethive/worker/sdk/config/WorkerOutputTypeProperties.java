package io.pockethive.worker.sdk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the worker's output type from configuration.
 * <p>
 * NFF: if {@code type} is null and {@code ioFromConfig=false}, the annotation
 * values on {@link PocketHiveWorker} are used instead.
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

