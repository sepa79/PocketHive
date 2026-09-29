package io.pockethive.work.config;

/**
 * Responsibility: identify an explicitly declared IO selection, settings block and supported output delivery modes.
 * Must not: infer a provider, parse settings or create adapter resources.
 * Contract: RESP-WORK-ADAPTER-SELECTION — docs/architecture/work-plane-boundaries.md#10-remaining-workplane-isolation-target--rabbit-plus-a-test-adapter.
 */
public interface WorkIoType {
    String name();
    String settingsKey();

    default boolean supportsDelayedDelivery() { return false; }

    default void requireDelivery(WorkDelivery delivery) {
        java.util.Objects.requireNonNull(delivery, "delivery");
        if (delivery.mode() == WorkDeliveryMode.DELAYED && !supportsDelayedDelivery()) {
            throw new IllegalArgumentException(name() + " output does not support DELAYED delivery");
        }
    }
}
