package io.pockethive.worker.sdk.output;

import io.pockethive.work.api.WorkItem;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WorkOutput implementation that discards results (used for scheduler-only workers).
 * <p>
 * Responsibility: implement explicitly selected NONE output without an output connection.
 * Must not: open an output connection or switch to another adapter.
 * Contract: RESP-WORK-NONE-OUTPUT — docs/architecture/runtime-responsibilities.md#resp-work-none-output.
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
