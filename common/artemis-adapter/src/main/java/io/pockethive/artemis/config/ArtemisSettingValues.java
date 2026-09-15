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
}
