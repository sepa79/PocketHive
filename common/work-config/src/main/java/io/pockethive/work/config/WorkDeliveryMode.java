package io.pockethive.work.config;

/**
 * Responsibility: name the neutral output delivery modes.
 * Must not: encode broker properties or select an adapter.
 * Contract: RESP-WORK-DELIVERY — docs/architecture/work-plane-boundaries.md#12-delayed-work-delivery.
 */
public enum WorkDeliveryMode {
    IMMEDIATE, DELAYED
}
