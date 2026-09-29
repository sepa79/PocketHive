package io.pockethive.scenarios;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class ScenarioBundleZipExporterTest {
    @TempDir Path root;
    @Test void exportsRelativeFileNamesAndExactBinaryBytesButNoDirectoryEntries() throws Exception {
        Files.createDirectories(root.resolve("nested/empty"));
        Files.writeString(root.resolve("scenario.yaml"), "id: example\n");
        byte[] binary = {0, 1, -1, 13, 10};
        Files.write(root.resolve("nested/data.bin"), binary);
        byte[] bytes = new ScenarioBundleZipExporter().export(root);
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                assertThat(entry.isDirectory()).isFalse();
                files.put(entry.getName(), zip.readAllBytes());
            }
        }
        assertThat(files.keySet()).containsExactlyInAnyOrder("scenario.yaml", "nested/data.bin");
        assertThat(files.get("nested/data.bin")).isEqualTo(binary);
        assertThat(files.get("scenario.yaml")).isEqualTo(Files.readAllBytes(root.resolve("scenario.yaml")));
    }
    @Test void emptyBundleProducesReadableEmptyArchive() throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(new ScenarioBundleZipExporter().export(root)))) {
            assertThat(zip.getNextEntry()).isNull();
        }
    }
}
