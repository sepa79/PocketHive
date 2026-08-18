package io.pockethive.mcp.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pockethive.mcp.domain.BundleFileManifest;
import io.pockethive.mcp.domain.BundleFileManifestEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import static org.mockito.Mockito.mockStatic;

class ZipBundleInspectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void verifiesEveryRegularFileWithoutFilteringExtensions() throws IOException {
        Path archive = zip(Map.of(
            "scenario.yaml", "id: db-smoke\n",
            "scripts/setup.sh", "#!/bin/sh\ntrue\n",
            "queries/check.sql", "select 1;\n",
            "config/profile.yml", "rate: 1\n",
            "README.md", "# smoke\n"));
        BundleFileManifest expected = manifest(Map.of(
            "scenario.yaml", "id: db-smoke\n",
            "scripts/setup.sh", "#!/bin/sh\ntrue\n",
            "queries/check.sql", "select 1;\n",
            "config/profile.yml", "rate: 1\n",
            "README.md", "# smoke\n"));

        ArchiveInspection inspection = inspector(10, 10_000, 8, 100).inspect(archive, expected);

        assertThat(inspection.manifest()).isEqualTo(expected);
        assertThat(inspection.archiveDigest()).startsWith("sha256:").hasSize(71);
        assertThat(inspection.expandedBytes()).isEqualTo(expected.files().stream()
            .mapToLong(BundleFileManifestEntry::byteCount).sum());
    }

    @Test
    void rejectsTraversalAbsoluteBackslashDuplicateAndExcessiveNesting() throws IOException {
        assertRejected(zip(Map.of("../escape.sql", "x")), "ARCHIVE_PATH_INVALID");
        assertRejected(zip(Map.of("/absolute.sql", "x")), "ARCHIVE_PATH_INVALID");
        assertRejected(zip(Map.of("C:drive.sql", "x")), "ARCHIVE_PATH_INVALID");
        assertRejected(zipWithJavaEntry("folder\\file.sql", "x"), "ARCHIVE_PATH_INVALID");
        assertRejected(zipEntries(List.of(Map.entry("same.sql", "one"), Map.entry("same.sql", "two"))),
            "ARCHIVE_PATH_DUPLICATE");
        Path nested = zip(Map.of("a/b/c/d/e.sql", "x"));
        assertThatThrownBy(() -> inspector(10, 10_000, 3, 100).inspect(nested, manifest(Map.of("a/b/c/d/e.sql", "x"))))
            .isInstanceOf(ArchiveRejectedException.class)
            .hasMessageContaining("ARCHIVE_NESTING_EXCEEDED");
    }

    @Test
    void rejectsSymlinksFileCountExpandedSizeRatioAndManifestMismatch() throws IOException {
        Path symlink = temporaryDirectory.resolve("symlink.zip");
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(symlink)) {
            ZipArchiveEntry entry = new ZipArchiveEntry("link");
            entry.setUnixMode(0120777);
            output.putArchiveEntry(entry);
            output.write("target".getBytes(StandardCharsets.UTF_8));
            output.closeArchiveEntry();
        }
        assertRejected(symlink, "ARCHIVE_ENTRY_TYPE_INVALID");

        Path fifo = temporaryDirectory.resolve("fifo.zip");
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(fifo)) {
            ZipArchiveEntry entry = new ZipArchiveEntry("named-pipe");
            entry.setUnixMode(0010644);
            output.putArchiveEntry(entry);
            output.write("pipe".getBytes(StandardCharsets.UTF_8));
            output.closeArchiveEntry();
        }
        assertRejected(fifo, "ARCHIVE_ENTRY_TYPE_INVALID");

        Path twoFiles = zip(Map.of("one", "1", "two", "2"));
        assertThatThrownBy(() -> inspector(1, 10_000, 8, 100).inspect(twoFiles,
            manifest(Map.of("one", "1", "two", "2"))))
            .isInstanceOf(ArchiveRejectedException.class).hasMessageContaining("ARCHIVE_FILE_COUNT_EXCEEDED");

        Path expanded = zip(Map.of("large", "x".repeat(1000)));
        assertThatThrownBy(() -> inspector(10, 999, 8, 100).inspect(expanded,
            manifest(Map.of("large", "x".repeat(1000)))))
            .isInstanceOf(ArchiveRejectedException.class).hasMessageContaining("ARCHIVE_EXPANDED_SIZE_EXCEEDED");
        assertThatThrownBy(() -> inspector(10, 10_000, 8, 2).inspect(expanded,
            manifest(Map.of("large", "x".repeat(1000)))))
            .isInstanceOf(ArchiveRejectedException.class).hasMessageContaining("ARCHIVE_COMPRESSION_RATIO_EXCEEDED");

        Path cumulative = zip(Map.of("first", "12", "second", "34"));
        assertThatThrownBy(() -> inspector(10, 3, 8, 100).inspect(cumulative,
            manifest(Map.of("first", "12", "second", "34"))))
            .isInstanceOf(ArchiveRejectedException.class).hasMessageContaining("ARCHIVE_EXPANDED_SIZE_EXCEEDED");

        Path changed = zip(Map.of("scenario.yaml", "actual"));
        assertThatThrownBy(() -> inspector(10, 10_000, 8, 100).inspect(changed,
            manifest(Map.of("scenario.yaml", "expected"))))
            .isInstanceOf(ArchiveRejectedException.class).hasMessageContaining("BUNDLE_MANIFEST_MISMATCH");
    }

    @Test
    void acceptsEveryConfiguredBoundaryExactly() throws IOException {
        Path oneFile = zip(Map.of("a/b/c/d", "x".repeat(1000)));
        BundleFileManifest expected = manifest(Map.of("a/b/c/d", "x".repeat(1000)));

        ArchiveInspection inspection = inspector(1, 1000, 3, 1000).inspect(oneFile, expected);

        assertThat(inspection.expandedBytes()).isEqualTo(1000);

        Path stored = storedZip("exact.bin", "1234567890");
        ArchiveInspection exactRatio = inspector(1, 10, 0, 1)
            .inspect(stored, manifest(Map.of("exact.bin", "1234567890")));
        assertThat(exactRatio.expandedBytes()).isEqualTo(10);

        Path empty = zip(Map.of());
        ArchiveInspection emptyInspection = inspector(1, 1, 0, 1)
            .inspect(empty, new BundleFileManifest(List.of()));
        assertThat(emptyInspection.expandedBytes()).isZero();

        Path withDirectory = zipEntries(List.of(
            new AbstractMap.SimpleImmutableEntry<>("scripts/", null),
            Map.entry("scripts/setup.sh", "true\n")));
        ArchiveInspection explicitDirectory = inspector(1, 10, 1, 100)
            .inspect(withDirectory, manifest(Map.of("scripts/setup.sh", "true\n")));
        assertThat(explicitDirectory.manifest().files()).hasSize(1);
    }

    @Test
    void rejectsNullAtTheFirstPathCharacter() throws IOException {
        assertRejected(zipWithJavaEntry("\0unsafe", "x"), "ARCHIVE_PATH_INVALID");
    }

    @Test
    void reportsUnreadableArchivesAndMissingSha256ProviderExplicitly() throws IOException {
        assertThatThrownBy(() -> inspector(1, 1, 0, 1).inspect(
            temporaryDirectory, new BundleFileManifest(List.of())))
            .isInstanceOf(ArchiveRejectedException.class).hasMessageContaining("ARCHIVE_READ_FAILED");

        Path archive = zip(Map.of("file", "value"));
        BundleFileManifest expected = manifest(Map.of("file", "value"));
        try (MockedStatic<MessageDigest> digests = mockStatic(MessageDigest.class)) {
            digests.when(() -> MessageDigest.getInstance("SHA-256"))
                .thenThrow(new NoSuchAlgorithmException("missing"));
            assertThatThrownBy(() -> inspector(1, 10, 0, 1).inspect(
                archive, expected))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SHA-256 is required by Java");
        }
    }

    private void assertRejected(Path path, String code) {
        assertThatThrownBy(() -> inspector(10, 10_000, 8, 100).inspect(path, new BundleFileManifest(List.of())))
            .isInstanceOf(ArchiveRejectedException.class).hasMessageContaining(code);
    }

    private ZipBundleInspector inspector(int files, long expanded, int nesting, int ratio) {
        return new ZipBundleInspector(files, expanded, nesting, ratio);
    }

    private Path zip(Map<String, String> files) throws IOException {
        return zipEntries(files.entrySet().stream().toList());
    }

    private Path zipEntries(List<Map.Entry<String, String>> files) throws IOException {
        Path archive = Files.createTempFile(temporaryDirectory, "bundle-", ".zip");
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(archive)) {
            for (Map.Entry<String, String> file : files) {
                ZipArchiveEntry entry = new ZipArchiveEntry(file.getKey());
                entry.setUnixMode(file.getValue() == null ? 0040755 : 0100644);
                output.putArchiveEntry(entry);
                if (file.getValue() != null) {
                    output.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                }
                output.closeArchiveEntry();
            }
        }
        return archive;
    }

    private Path zipWithJavaEntry(String name, String content) throws IOException {
        Path archive = Files.createTempFile(temporaryDirectory, "raw-name-", ".zip");
        try (java.util.zip.ZipOutputStream output = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new java.util.zip.ZipEntry(name));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return archive;
    }

    private Path storedZip(String name, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes);
        Path archive = Files.createTempFile(temporaryDirectory, "stored-", ".zip");
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(archive)) {
            ZipArchiveEntry entry = new ZipArchiveEntry(name);
            entry.setMethod(ZipArchiveEntry.STORED);
            entry.setSize(bytes.length);
            entry.setCompressedSize(bytes.length);
            entry.setCrc(crc.getValue());
            entry.setUnixMode(0100644);
            output.putArchiveEntry(entry);
            output.write(bytes);
            output.closeArchiveEntry();
        }
        return archive;
    }

    private static BundleFileManifest manifest(Map<String, String> files) {
        Map<String, String> ordered = new LinkedHashMap<>();
        files.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return new BundleFileManifest(ordered.entrySet().stream()
            .map(entry -> BundleFileManifestEntry.fromBytes(entry.getKey(),
                entry.getValue().getBytes(StandardCharsets.UTF_8)))
            .toList());
    }
}
