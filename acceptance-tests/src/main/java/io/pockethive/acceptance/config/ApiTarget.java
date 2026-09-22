package io.pockethive.acceptance.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Responsibility: hold resolved common API session settings.
 * Must not: read configuration, add defaults or require a lifecycle fixture.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#resp-acceptance-target.
 */
public record ApiTarget(URI ingress, String username, Duration requestTimeout, Path evidenceDirectory) {}
