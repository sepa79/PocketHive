package io.pockethive.acceptance.config;

import java.time.Duration;

/**
 * Responsibility: hold explicit slow TCP mock selection, credentials and negative capture window.
 * Must not: supply defaults or configure the mock server.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#resp-acceptance-target.
 */
public record TcpTimeoutTarget(AcceptanceTarget lifecycle, String mappingId, String mockUsername,
                               String mockPassword, Duration quietWindow) {}
