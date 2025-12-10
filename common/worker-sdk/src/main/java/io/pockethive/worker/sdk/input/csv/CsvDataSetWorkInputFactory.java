package io.pockethive.worker.sdk.input.csv;

import io.pockethive.controlplane.ControlPlaneIdentity;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.input.WorkInput;
import io.pockethive.worker.sdk.input.WorkInputFactory;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerRuntime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;

public final class CsvDataSetWorkInputFactory implements WorkInputFactory, Ordered {

    private final WorkerRuntime workerRuntime;
    private final WorkerControlPlaneRuntime controlPlaneRuntime;
    private final ControlPlaneIdentity identity;

    public CsvDataSetWorkInputFactory(
        WorkerRuntime workerRuntime,
        WorkerControlPlaneRuntime controlPlaneRuntime,
        ControlPlaneIdentity identity
    ) {
        this.workerRuntime = Objects.requireNonNull(workerRuntime, "workerRuntime");
        this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    @Override
    public boolean supports(WorkerDefinition definition) {
        return definition.input() == WorkerInputType.CSV_DATASET;
    }

    @Override
    public WorkInput create(WorkerDefinition definition, WorkInputConfig config) {
        Logger logger = LoggerFactory.getLogger(definition.beanType());
        CsvDataSetInputProperties properties = config instanceof CsvDataSetInputProperties props
            ? props
            : new CsvDataSetInputProperties();
        return new CsvDataSetWorkInput(definition, controlPlaneRuntime, workerRuntime, identity, properties, logger);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private CsvDataSetInputProperties buildProperties_UNUSED() {
        CsvDataSetInputProperties properties = new CsvDataSetInputProperties();
        return properties;
    }
}
