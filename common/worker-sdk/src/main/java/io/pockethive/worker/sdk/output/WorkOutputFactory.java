package io.pockethive.worker.sdk.output;

import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;

/**
 * SPI that creates {@link WorkOutput} instances for a given worker/transport combination.
 */
public interface WorkOutputFactory {

    boolean supports(WorkerDefinition definition);

    WorkOutput create(WorkerDefinition definition, WorkOutputConfig config);
}
