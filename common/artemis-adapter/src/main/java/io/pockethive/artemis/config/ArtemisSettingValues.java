package io.pockethive.artemis.config;

/**
 * Responsibility: validate scalar values shared by Artemis typed settings.
 * Must not: read configuration sources, supply fallback values or open clients.
 * Contract: RESP-ARTEMIS-CONFIGURATION — docs/architecture/runtime-responsibilities.md#resp-artemis-configuration.
 */
public final class ArtemisSettingValues {
    private ArtemisSettingValues() { }

    public static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be nonblank");
        }
        return value.trim();
    }

    public static long positive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
    public static String text(Object value, String field) {
        if (!(value instanceof String text)) throw new IllegalArgumentException(field + " must be text");
        return requiredText(text, field);
    }

    public static int integer(Object value, String field) {
        try {
            if (value instanceof Number number) return new java.math.BigDecimal(number.toString()).intValueExact();
            if (value instanceof String text) return Integer.parseInt(text.trim());
        } catch (ArithmeticException | NumberFormatException invalid) {
            throw new IllegalArgumentException(field + " must be a 32-bit integer");
        }
        throw new IllegalArgumentException(field + " must be a 32-bit integer");
    }

    public static Integer nonnegative(Integer value, String field) {
        if (value == null || value < 0) throw new IllegalArgumentException(field + " must be nonnegative and explicitly configured");
        return value;
    }

    public static Boolean requiredBoolean(Boolean value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " must be explicitly configured");
        return value;
    }

    public static Boolean booleanValue(Object value, String field) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text.trim())) return true;
            if ("false".equalsIgnoreCase(text.trim())) return false;
        }
        throw new IllegalArgumentException(field + " must be true or false");
    }
}
