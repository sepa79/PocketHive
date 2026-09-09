package io.pockethive.work.config;

/**
 * Responsibility: distinguish authoring validation from resolved runtime validation.
 * Must not: imply that deferred authoring constraints have passed runtime validation.
 * Contract: RESP-WORK-REDIS-ROUTES — docs/architecture/runtime-responsibilities.md#resp-work-redis-routes.
 */
public enum WorkConfigurationMode {
    AUTHORING,
    RESOLVED
}
