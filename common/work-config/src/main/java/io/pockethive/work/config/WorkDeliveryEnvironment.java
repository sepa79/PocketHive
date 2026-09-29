package io.pockethive.work.config;

import java.util.Map;

/**
 * Responsibility: project accepted delivery settings into worker startup environment.
 * Must not: reparse settings, choose defaults or resolve adapter addresses.
 * Contract: RESP-WORK-DELIVERY — docs/architecture/work-plane-boundaries.md#12-delayed-work-delivery.
 */
public final class WorkDeliveryEnvironment {
    public static final String PREFIX = "pockethive.outputs.delivery";
    public static final String MODE_ENV = "POCKETHIVE_OUTPUTS_DELIVERY_MODE";
    public static final String DELAY_ENV = "POCKETHIVE_OUTPUTS_DELIVERY_DELAYMS";

    public Map<String, String> encode(WorkDelivery delivery) {
        return delivery.mode() == WorkDeliveryMode.IMMEDIATE ? Map.of(MODE_ENV, delivery.mode().name())
            : Map.of(MODE_ENV, delivery.mode().name(), DELAY_ENV, Long.toString(delivery.delayMs()));
    }
}
