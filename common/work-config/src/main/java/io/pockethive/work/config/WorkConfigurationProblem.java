package io.pockethive.work.config;

/**
 * Responsibility: expose a canonical configuration failure as a path and message.
 * Must not: decide parsing rules or scenario diagnostic severity.
 * Contract: RESP-WORK-REDIS-ROUTES — docs/architecture/runtime-responsibilities.md#resp-work-redis-routes.
 */
public record WorkConfigurationProblem(String path, String message) {
}
