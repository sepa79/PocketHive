package io.pockethive.work.config;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Responsibility: parse neutral delivery declarations and own their sole IMMEDIATE default.
 * Must not: select transports, read environment or schedule messages.
 * Contract: RESP-WORK-DELIVERY — docs/architecture/work-plane-boundaries.md#12-delayed-work-delivery.
 */
public final class WorkDeliveryParser {
    public static final String DELIVERY = "delivery";
    public static final String MODE = "mode";
    public static final String DELAY_MS = "delayMs";
    public static final String PATH = WorkConfigurationFields.OUTPUTS + "." + DELIVERY;

    public WorkDelivery parseOutput(Map<?, ?> output) {
        return output.containsKey(DELIVERY) ? parse(output.get(DELIVERY)) : WorkDelivery.IMMEDIATE;
    }

    public WorkDelivery parse(Object value) {
        if (!(value instanceof Map<?, ?> fields)) throw invalid("must be an object");
        if (fields.keySet().stream().anyMatch(key -> !(key instanceof String) || !Set.of(MODE, DELAY_MS).contains(key))) {
            throw invalid("contains unknown fields");
        }
        WorkDeliveryMode mode;
        try {
            if (!(fields.get(MODE) instanceof String text)) throw invalid("mode must be IMMEDIATE or DELAYED");
            mode = WorkDeliveryMode.valueOf(text);
        } catch (IllegalArgumentException failure) {
            throw invalid("mode must be IMMEDIATE or DELAYED");
        }
        if (mode == WorkDeliveryMode.IMMEDIATE) {
            if (fields.containsKey(DELAY_MS)) throw invalid("IMMEDIATE forbids delayMs");
            return WorkDelivery.IMMEDIATE;
        }
        Object delay = fields.get(DELAY_MS);
        try {
            if (!(delay instanceof Number) && !(delay instanceof String)) throw invalid("delayMs must be a positive integer");
            return new WorkDelivery(mode, new BigDecimal(delay.toString()).longValueExact());
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw invalid("delayMs must be a positive integer within the 64-bit range");
        }
    }

    public Map<String, Object> configuration(WorkDelivery delivery) {
        return delivery.mode() == WorkDeliveryMode.IMMEDIATE ? Map.of(MODE, delivery.mode().name())
            : Map.of(MODE, delivery.mode().name(), DELAY_MS, delivery.delayMs());
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(PATH + " " + reason);
    }
}
