package io.pockethive.worker.sdk.input;

import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;

/**
 * SPI for creating {@link WorkInput} instances for a worker definition.
 */
public interface WorkInputFactory {

    boolean supports(WorkerDefinition definition);

    WorkInput create(WorkerDefinition definition, WorkInputConfig config);
}
