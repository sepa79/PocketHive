package io.pockethive.clearingexport;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class LocalDirectoryClearingExportSink implements ClearingExportSink {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final Object manifestLock = new Object();

  @Override
  public void writeFile(ClearingExportWorkerConfig config, ClearingRenderedFile file) throws Exception {
    Path targetDir = Path.of(config.localTargetDir());
    Files.createDirectories(targetDir);

    Path finalPath = targetDir.resolve(file.fileName());
    Path tmpPath = targetDir.resolve(file.fileName() + config.localTempSuffix());

    Files.writeString(
        tmpPath,
        file.content(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
    );

    moveAtomically(tmpPath, finalPath);

    if (config.writeManifest()) {
      appendManifest(config, file, finalPath);
    }
  }

  private void appendManifest(ClearingExportWorkerConfig config, ClearingRenderedFile file, Path finalPath) throws IOException {
    Path manifest = resolveManifestPath(config);
    Files.createDirectories(manifest.getParent());

    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("fileName", file.fileName());
    entry.put("recordCount", file.recordCount());
    entry.put("bytes", file.bytesUtf8());
    entry.put("createdAt", file.createdAt().toString());
    entry.put("path", finalPath.toAbsolutePath().toString());

    String line = objectMapper.writeValueAsString(entry) + "\n";
    synchronized (manifestLock) {
      Files.writeString(
          manifest,
          line,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND,
          StandardOpenOption.WRITE
      );
    }
  }

  private Path resolveManifestPath(ClearingExportWorkerConfig config) {
    Path configured = Path.of(config.localManifestPath());
    if (configured.isAbsolute()) {
      return configured;
    }
    return Path.of(config.localTargetDir()).resolve(configured);
  }

  private static void moveAtomically(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
