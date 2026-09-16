package io.pockethive.acceptance.config;

import java.time.Duration;

/**
 * Responsibility: hold the resolved time limits.
 * Must not: resolve configuration or supply defaults.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#resp-acceptance-target.
 */
public record WaitLimits(Duration request, Duration operation, Duration capture, Duration poll) {}
