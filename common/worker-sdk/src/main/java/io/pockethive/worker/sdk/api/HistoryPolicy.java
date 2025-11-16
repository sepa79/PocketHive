package io.pockethive.worker.sdk.api;

/**
 * Controls how much step history a {@link WorkItem} should retain.
 * <p>
 * This is configured per worker via {@code pockethive.worker.history-policy} in Scenario
 * YAML or service configuration. The default is {@link #FULL}.
 */
public enum HistoryPolicy {

    /**
     * Keep all recorded steps for the item until they are cleared explicitly.
     */
    FULL,

    /**
     * Keep only the latest step; previous steps may be discarded.
     */
    LATEST_ONLY,

    /**
     * Do not retain step history; the item behaves like a single-payload message.
     */
    DISABLED
}

