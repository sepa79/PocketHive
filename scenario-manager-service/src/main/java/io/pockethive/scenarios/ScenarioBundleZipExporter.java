package io.pockethive.scenarios;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;

/**
 * Responsibility: encode the resolved bundle filesystem tree as ZIP bytes.
 * Must not: select bundle identity, authorize, validate or publish bundles.
 * Contract: RESP-SCENARIO-BUNDLE-DOWNLOAD — docs/architecture/runtime-responsibilities.md#resp-scenario-bundle-download.
 */
@Component
public class ScenarioBundleZipExporter {
    public byte[] export(Path root) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out);
             Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (Files.isDirectory(path)) continue;
                String name = root.relativize(path).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(name));
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
