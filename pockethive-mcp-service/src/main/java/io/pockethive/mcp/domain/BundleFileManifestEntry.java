package io.pockethive.mcp.domain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record BundleFileManifestEntry(String path, long byteCount, String sha256) {
    public BundleFileManifestEntry {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(sha256, "sha256");
        if (path.isBlank() || byteCount < 0 || !sha256.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("BUNDLE_MANIFEST_ENTRY_INVALID");
        }
    }

    public static BundleFileManifestEntry fromBytes(String path, byte[] content) {
        Objects.requireNonNull(content, "content");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            return new BundleFileManifestEntry(path, content.length,
                "sha256:" + HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
        }
    }
}
