package io.pockethive.mcp.application;

import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.BundleFileManifestEntry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

/**
 * Responsibility: Validate and inspect bounded Scenario Bundle archives before owner upload.
 * Must not: Depend on HTTP, MCP transport, or persistence implementations.
 * Contract: docs/mcp/README.md.
 */

public final class ZipBundleInspector {
    private static final int UNIX_FILE_TYPE_MASK = 0170000;
    private static final int UNIX_REGULAR_FILE = 0100000;

    private final int maximumFiles;
    private final long maximumExpandedBytes;
    private final int maximumNesting;
    private final int maximumCompressionRatio;

    public ZipBundleInspector(int maximumFiles, long maximumExpandedBytes,
                              int maximumNesting, int maximumCompressionRatio) {
        this.maximumFiles = maximumFiles;
        this.maximumExpandedBytes = maximumExpandedBytes;
        this.maximumNesting = maximumNesting;
        this.maximumCompressionRatio = maximumCompressionRatio;
    }

    public ArchiveInspection inspect(Path archive, BundleFileManifest expectedManifest) {
        String archiveDigest = digestFile(archive);
        List<BundleFileManifestEntry> files = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        long expanded = 0;
        long compressed = 0;
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                String path = safePath(entry);
                if (!paths.add(path)) {
                    throw new ArchiveRejectedException("ARCHIVE_PATH_DUPLICATE");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                rejectUnsafeType(zip, entry);
                if (files.size() >= maximumFiles) {
                    throw new ArchiveRejectedException("ARCHIVE_FILE_COUNT_EXCEEDED");
                }
                FileDigest file = digestEntry(zip, entry, maximumExpandedBytes - expanded);
                expanded += file.byteCount();
                compressed += Math.max(0, entry.getCompressedSize());
                files.add(new BundleFileManifestEntry(path, file.byteCount(), file.digest()));
            }
        } catch (ArchiveRejectedException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ArchiveRejectedException("ARCHIVE_INVALID", exception);
        }
        if (expanded > 0 && (compressed == 0 || expanded / (double) compressed > maximumCompressionRatio)) {
            throw new ArchiveRejectedException("ARCHIVE_COMPRESSION_RATIO_EXCEEDED");
        }
        BundleFileManifest actual = new BundleFileManifest(files);
        if (!actual.equals(expectedManifest)) {
            throw new ArchiveRejectedException("BUNDLE_MANIFEST_MISMATCH");
        }
        return new ArchiveInspection(archiveDigest, actual, expanded);
    }

    private String safePath(ZipArchiveEntry entry) {
        String rawPath = entry.getName();
        String encodedName = entry.getRawName() == null ? rawPath
            : new String(entry.getRawName(), java.nio.charset.StandardCharsets.UTF_8);
        if (entry.isDirectory() && rawPath != null && rawPath.endsWith("/")) {
            rawPath = rawPath.substring(0, rawPath.length() - 1);
            encodedName = encodedName.substring(0, encodedName.length() - 1);
        }
        if (rawPath == null || rawPath.isBlank() || rawPath.indexOf('\0') >= 0
            || rawPath.startsWith("/") || rawPath.startsWith("\\") || rawPath.contains("\\")
            || encodedName.contains("\\")) {
            throw new ArchiveRejectedException("ARCHIVE_PATH_INVALID");
        }
        String normalized = Normalizer.normalize(rawPath, Normalizer.Form.NFC);
        String[] segments = normalized.split("/", -1);
        if (Math.decrementExact(segments.length) > maximumNesting) {
            throw new ArchiveRejectedException("ARCHIVE_NESTING_EXCEEDED");
        }
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new ArchiveRejectedException("ARCHIVE_PATH_INVALID");
            }
        }
        if (segments[0].matches("^[A-Za-z]:.*")) {
            throw new ArchiveRejectedException("ARCHIVE_PATH_INVALID");
        }
        return normalized;
    }

    private static void rejectUnsafeType(ZipFile zip, ZipArchiveEntry entry) {
        int fileType = entry.getUnixMode() & UNIX_FILE_TYPE_MASK;
        if (entry.isUnixSymlink() || (fileType != 0 && fileType != UNIX_REGULAR_FILE)
            || entry.getGeneralPurposeBit().usesEncryption() || !zip.canReadEntryData(entry)) {
            throw new ArchiveRejectedException("ARCHIVE_ENTRY_TYPE_INVALID");
        }
    }

    private static FileDigest digestEntry(ZipFile zip, ZipArchiveEntry entry, long remaining) throws IOException {
        MessageDigest digest = sha256();
        long count = 0;
        byte[] buffer = new byte[8192];
        try (InputStream input = new DigestInputStream(zip.getInputStream(entry), digest)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > remaining) {
                    throw new ArchiveRejectedException("ARCHIVE_EXPANDED_SIZE_EXCEEDED");
                }
            }
        }
        return new FileDigest(count, "sha256:" + HexFormat.of().formatHex(digest.digest()));
    }

    private static String digestFile(Path archive) {
        MessageDigest digest = sha256();
        try (InputStream input = new DigestInputStream(Files.newInputStream(archive), digest)) {
            input.transferTo(java.io.OutputStream.nullOutputStream());
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new ArchiveRejectedException("ARCHIVE_READ_FAILED", exception);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
        }
    }

    private record FileDigest(long byteCount, String digest) {
    }
}
