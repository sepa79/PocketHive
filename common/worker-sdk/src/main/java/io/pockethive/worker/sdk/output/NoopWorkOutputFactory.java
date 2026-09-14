package io.pockethive.worker.sdk.output;

import io.pockethive.work.api.transport.WorkOutput;

import io.pockethive.work.config.binding.WorkOutputConfig;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.work.config.WorkerOutputType;
import org.springframework.core.Ordered;

/**
 * Responsibility: compose the explicitly selected NONE output for one worker.
 * Must not: choose another adapter or open transport connections.
 * Contract: RESP-WORK-NONE-OUTPUT — docs/architecture/runtime-responsibilities.md#resp-work-none-output.
 */
public final class NoopWorkOutputFactory implements WorkOutputFactory, Ordered {

    @Override
    public boolean supports(WorkerDefinition definition) {
        return definition.outputType() == WorkerOutputType.NONE;
    }

    @Override
    public WorkOutput create(WorkerDefinition definition, WorkOutputConfig config) {
        return new NoopWorkOutput(definition.beanName());
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
