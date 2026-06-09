package io.pockethive.worker.sdk.config;

import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Validates scheduler input configuration before it is accepted onto the control plane.
 */
public final class SchedulerInputConfigValidator {

    private static final String ERROR_PREFIX = "inputs.scheduler.maxMessages";

    private SchedulerInputConfigValidator() {
    }

    public static void validate(Map<String, Object> rawConfig, TemplateRenderer renderer) {
        Map<?, ?> schedulerMap = schedulerMap(rawConfig);
        if (schedulerMap == null) {
            return;
        }
        resolveMaxMessages(schedulerMap.get("maxMessages"), rawConfig, renderer);
    }

    public static OptionalLong resolveMaxMessages(Object rawValue, Map<String, Object> rawConfig, TemplateRenderer renderer) {
        if (rawValue == null) {
            return OptionalLong.empty();
        }
        if (rawValue instanceof Number number) {
            return OptionalLong.of(requireWholeNumber(number));
        }
        if (!(rawValue instanceof String text)) {
            throw new IllegalArgumentException(ERROR_PREFIX + " must be a non-negative integer or numeric template");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return OptionalLong.empty();
        }
        String resolved = trimmed;
        if (trimmed.contains("{{") || trimmed.contains("{%")) {
            TemplateRenderer effectiveRenderer = renderer == null ? new PebbleTemplateRenderer() : renderer;
            Map<String, Object> context = new HashMap<>();
            Object vars = rawConfig == null ? null : rawConfig.get("vars");
            if (vars != null) {
                context.put("vars", vars);
            }
            resolved = effectiveRenderer.render(trimmed, context).trim();
        }
        if (!resolved.matches("\\d+")) {
            throw new IllegalArgumentException(
                ERROR_PREFIX + " must resolve to a non-negative whole number, got '" + resolved + "'");
        }
        try {
            return OptionalLong.of(Long.parseLong(resolved));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(ERROR_PREFIX + " is outside the supported 64-bit range", ex);
        }
    }

    private static Map<?, ?> schedulerMap(Map<String, Object> rawConfig) {
        if (rawConfig == null || rawConfig.isEmpty()) {
            return null;
        }
        Object inputs = rawConfig.get("inputs");
        if (!(inputs instanceof Map<?, ?> inputsMap)) {
            return null;
        }
        Object scheduler = inputsMap.get("scheduler");
        if (!(scheduler instanceof Map<?, ?> schedulerMap)) {
            return null;
        }
        return schedulerMap;
    }

    private static long requireWholeNumber(Number number) {
        try {
            if (number instanceof BigInteger bigInteger) {
                return requireNonNegative(bigInteger.longValueExact(), number);
            }
            if (number instanceof BigDecimal bigDecimal) {
                return requireNonNegative(bigDecimal.longValueExact(), number);
            }
            if (number instanceof Byte
                || number instanceof Short
                || number instanceof Integer
                || number instanceof Long
                || number instanceof java.util.concurrent.atomic.AtomicInteger
                || number instanceof java.util.concurrent.atomic.AtomicLong) {
                return requireNonNegative(number.longValue(), number);
            }
            if (number instanceof Float || number instanceof Double) {
                double value = number.doubleValue();
                if (!Double.isFinite(value) || value < 0.0 || Math.rint(value) != value || value > Long.MAX_VALUE) {
                    throw nonWholeNumber(number);
                }
                return (long) value;
            }
            return requireNonNegative(new BigDecimal(number.toString()).longValueExact(), number);
        } catch (ArithmeticException | NumberFormatException ex) {
            throw nonWholeNumber(number);
        }
    }

    private static long requireNonNegative(long value, Number original) {
        if (value < 0L) {
            throw nonWholeNumber(original);
        }
        return value;
    }

    private static IllegalArgumentException nonWholeNumber(Number value) {
        return new IllegalArgumentException(
            ERROR_PREFIX + " must be a non-negative whole number, got '" + value + "'");
    }
}
