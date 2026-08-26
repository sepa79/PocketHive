package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.BundleFileManifest;

/**
 * Responsibility: Carry immutable archive inspection application data.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public record ArchiveInspection(String archiveDigest, BundleFileManifest manifest, long expandedBytes) {
}
