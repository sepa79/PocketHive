package io.pockethive.worker.sdk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the worker's input type from configuration.
 * <p>
 * NFF: if {@code type} is null and {@code ioFromConfig=false}, the annotation
 * values on {@link PocketHiveWorker} are used instead.
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

