package io.pockethive.work.config;

import java.util.Objects;

/**
 * Responsibility: retain one validated neutral publication delay.
 * Must not: read clocks, schedule work or own message payloads.
 * Contract: RESP-WORK-DELIVERY — docs/architecture/work-plane-boundaries.md#12-delayed-work-delivery.
 */
public record WorkDelivery(WorkDeliveryMode mode, long delayMs) {
    public static final WorkDelivery IMMEDIATE = new WorkDelivery(WorkDeliveryMode.IMMEDIATE, 0);

    public WorkDelivery {
        Objects.requireNonNull(mode, "mode");
        if (mode == WorkDeliveryMode.IMMEDIATE && delayMs != 0) {
            throw new IllegalArgumentException("IMMEDIATE delivery has no delay");
        }
        if (mode == WorkDeliveryMode.DELAYED && delayMs <= 0) {
            throw new IllegalArgumentException("DELAYED delivery requires positive delayMs");
        }
    }
}
