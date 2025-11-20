package io.pockethive.worker.sdk.output;

import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WorkOutput implementation that discards results (used for scheduler-only workers).
 */
public final class NoopWorkOutput implements WorkOutput {

    private static final Logger log = LoggerFactory.getLogger(NoopWorkOutput.class);

    @Override
    public void publish(WorkItem item, WorkerDefinition definition) {
        if (log.isDebugEnabled()) {
            log.debug("Dropping worker result for '{}' because no output is configured", definition.beanName());
        }
    }
}
