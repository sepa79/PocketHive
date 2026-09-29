package io.pockethive.work.api;

/**
 * Controls how much step history a {@link WorkItem} should retain.
 * <p>
 * The worker runtime selects the policy from accepted worker {@code config.historyPolicy}.
 * An absent field defaults to {@link #FULL}; configuration parsing belongs to the runtime.
 * <p>
 * Responsibility: define the HistoryPolicy contract.
 * Must not: configure transport clients or own adapter lifecycle.
 * Contract: RESP-WORK-ITEM — docs/architecture/runtime-responsibilities.md#resp-work-item.
 */
public enum HistoryPolicy {

    /**
     * Keep all recorded steps for the item until they are cleared explicitly.
     */
    FULL,

    /**
     * Keep only the latest step; previous steps may be discarded.
     */
    LATEST_ONLY
}

