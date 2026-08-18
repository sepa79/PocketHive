package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.BundleFileManifest;

public record ArchiveInspection(String archiveDigest, BundleFileManifest manifest, long expandedBytes) {
}
