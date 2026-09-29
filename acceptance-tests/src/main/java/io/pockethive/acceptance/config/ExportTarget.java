package io.pockethive.acceptance.config;

import java.nio.file.Path;

/**
 * Responsibility: retain explicit lifecycle, Redis and host-visible runtime filesystem settings.
 * Must not: discover mounts, infer remote paths or construct swarm output paths.
 * Contract: RESP-ACCEPTANCE-TARGET — docs/architecture/acceptance-tests.md#resp-acceptance-export-files.
 */
public record ExportTarget(AcceptanceTarget lifecycle, String connectionId, Path runtimeRoot) { }
