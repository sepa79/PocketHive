package io.pockethive.worker.sdk.output;

import io.pockethive.work.api.transport.WorkOutput;

import io.pockethive.work.api.WorkItem;
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

    private final String workerName;

    public NoopWorkOutput(String workerName) {
        this.workerName = java.util.Objects.requireNonNull(workerName, "workerName");
    }

    @Override
    public void publish(WorkItem item) {
        if (log.isDebugEnabled()) {
            log.debug("Dropping worker result for '{}' because no output is configured", workerName);
        }
    }
}
