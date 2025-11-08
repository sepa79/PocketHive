package io.pockethive.worker.sdk.output;

import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import org.springframework.core.Ordered;

public final class NoopWorkOutputFactory implements WorkOutputFactory, Ordered {

    @Override
    public boolean supports(WorkerDefinition definition) {
        return definition.outputType() == WorkerOutputType.NONE;
    }

    @Override
    public WorkOutput create(WorkerDefinition definition, WorkOutputConfig config) {
        return new NoopWorkOutput();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
