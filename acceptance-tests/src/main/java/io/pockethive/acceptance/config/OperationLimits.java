package io.pockethive.acceptance.config;

import java.time.Duration;

/**
 * Responsibility: hold resolved request, operation and polling limits without capture settings.
 * Must not: resolve configuration or supply defaults.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#resp-acceptance-target.
 */
public record OperationLimits(Duration request, Duration operation, Duration poll) {}
