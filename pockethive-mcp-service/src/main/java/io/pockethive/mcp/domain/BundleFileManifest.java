package io.pockethive.mcp.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Responsibility: Model the BundleFileManifest MCP domain concept and enforce its local invariants.
 * Must not: Access transport, configuration, or infrastructure adapters.
 * Contract: docs/mcp/README.md.
 */

public record BundleFileManifest(List<BundleFileManifestEntry> files) {
    public BundleFileManifest {
        Objects.requireNonNull(files, "files");
        files = files.stream().sorted((left, right) -> left.path().compareTo(right.path())).toList();
        Set<String> paths = new HashSet<>();
        if (files.stream().anyMatch(file -> !paths.add(file.path()))) {
            throw new IllegalArgumentException("BUNDLE_MANIFEST_PATH_DUPLICATE");
        }
    }
}
