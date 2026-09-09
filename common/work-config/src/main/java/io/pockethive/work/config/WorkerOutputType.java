package io.pockethive.work.config;

/**
 * Responsibility: declare supported output selections and their canonical settings keys.
 * Must not: select or construct adapters, or bind environment properties.
 * Contract: RESP-WORK-PATCH-POLICY — docs/architecture/runtime-responsibilities.md#resp-work-patch-policy.
 */
public enum WorkerOutputType {
    NONE("none"),
    RABBITMQ("rabbit"),
    REDIS("redis");

    private final String settingsKey;

    WorkerOutputType(String settingsKey) {
        this.settingsKey = settingsKey;
    }

    public String settingsKey() {
        return settingsKey;
    }
}
