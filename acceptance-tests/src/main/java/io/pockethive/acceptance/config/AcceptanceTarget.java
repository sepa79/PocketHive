package io.pockethive.acceptance.config;

import java.net.URI;
import java.nio.file.Path;

/**
 * Responsibility: hold the effective target resolved once for a test run.
 * Must not: read environment settings or add defaults.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#resp-acceptance-target.
 */
public record AcceptanceTarget(URI ingress, String username, WaitLimits limits,
    HttpFixture fixture, Path evidenceDirectory) {}
