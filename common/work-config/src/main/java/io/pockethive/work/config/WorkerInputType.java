package io.pockethive.work.config;

/**
 * Responsibility: declare supported input selections and their canonical settings keys.
 * Must not: select or construct adapters, or bind environment properties.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
public enum WorkerInputType implements WorkIoType {
    RABBITMQ("rabbit"),
    SCHEDULER("scheduler"),
    REDIS_DATASET("redis"),
    CSV_DATASET("csv");

    private final String settingsKey;

    WorkerInputType(String settingsKey) {
        this.settingsKey = settingsKey;
    }

    public String settingsKey() {
        return settingsKey;
    }
}
