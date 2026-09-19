package io.pockethive.rabbit.config;

/**
 * Responsibility: own Rabbit setting value normalization and scalar constraints for parsers and typed snapshots.
 * Must not: select adapters, resolve topology or create transport clients.
 * Contract: RESP-WORK-RABBIT-SETTINGS — docs/architecture/runtime-responsibilities.md#resp-work-rabbit-settings.
 */
public final class RabbitSettingValues {
    private RabbitSettingValues() { }
    public static String requiredText(Object value) {
        if (!(value instanceof String text) || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Must be nonblank text.");
        }
        return text.trim();
    }
    public static int positiveInteger(Object value) {
        if (!(value instanceof Integer integer) || integer < 1) {
            throw new IllegalArgumentException("Must be a positive 32-bit integer.");
        }
        return integer;
    }
    public static void consumerPolicy(int concurrentConsumers, boolean exclusive) {
        if (exclusive && concurrentConsumers != 1) {
            throw new IllegalArgumentException("Exclusive consumers require concurrentConsumers=1.");
        }
    }
    public static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) return true;
            if ("false".equalsIgnoreCase(text)) return false;
        }
        throw new IllegalArgumentException("Must be true or false.");
    }
}
