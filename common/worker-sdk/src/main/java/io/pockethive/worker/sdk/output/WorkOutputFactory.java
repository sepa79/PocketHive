package io.pockethive.worker.sdk.output;

import io.pockethive.work.api.transport.WorkOutput;

import io.pockethive.work.config.binding.WorkOutputConfig;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;

/**
 * SPI that creates {@link WorkOutput} instances for a given worker/transport combination.
 */
public interface WorkOutputFactory {

    boolean supports(WorkerDefinition definition);

    WorkOutput create(WorkerDefinition definition, WorkOutputConfig config);
}
