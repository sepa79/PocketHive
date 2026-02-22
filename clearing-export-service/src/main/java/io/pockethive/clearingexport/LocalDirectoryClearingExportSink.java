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
  public ClearingExportSinkWriteResult writeFile(
      ClearingExportWorkerConfig config,
      ClearingRenderedFile file
  ) throws Exception {
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
      appendManifest(
          config,
          file.fileName(),
          file.recordCount(),
          file.bytesUtf8(),
          file.createdAt(),
          finalPath);
    }
    return new ClearingExportSinkWriteResult(
        file.fileName(),
        file.recordCount(),
        file.bytesUtf8(),
        file.createdAt(),
        finalPath.toAbsolutePath().toString());
  }

  @Override
  public ClearingExportSinkWriteResult finalizeStreamingFile(
      ClearingExportWorkerConfig config,
      String fileName,
      String footerLine,
      String lineSeparator,
      int recordCount,
      java.time.Instant createdAt
  ) throws Exception {
    Path targetDir = Path.of(config.localTargetDir());
    Files.createDirectories(targetDir);
    Path finalPath = targetDir.resolve(fileName);
    if (Files.exists(finalPath)) {
      return new ClearingExportSinkWriteResult(
          fileName,
          recordCount,
          Files.size(finalPath),
          createdAt,
          finalPath.toAbsolutePath().toString());
    }

    Path tempPath = targetDir.resolve(fileName + config.localTempSuffix());
    if (!Files.exists(tempPath)) {
      throw new IllegalStateException("Streaming temp file is missing before finalize: " + tempPath);
    }
    String footerPayload = footerLine + lineSeparator;
    if (!endsWith(tempPath, footerPayload)) {
      Files.writeString(
          tempPath,
          footerPayload,
          StandardCharsets.UTF_8,
          StandardOpenOption.APPEND,
          StandardOpenOption.WRITE
      );
    }
    moveAtomically(tempPath, finalPath);
    long bytes = Files.size(finalPath);
    if (config.writeManifest()) {
      appendManifest(
          config,
          fileName,
          recordCount,
          bytes,
          createdAt,
          finalPath);
    }
    return new ClearingExportSinkWriteResult(
        fileName,
        recordCount,
        bytes,
        createdAt,
        finalPath.toAbsolutePath().toString());
  }

  @Override
  public void openStreamingFile(
      ClearingExportWorkerConfig config,
      String fileName,
      String headerLine,
      String lineSeparator
  ) throws Exception {
    Path targetDir = Path.of(config.localTargetDir());
    Files.createDirectories(targetDir);
    Path tempPath = targetDir.resolve(fileName + config.localTempSuffix());
    Files.writeString(
        tempPath,
        headerLine + lineSeparator,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE
    );
  }

  @Override
  public void appendStreamingRecord(
      ClearingExportWorkerConfig config,
      String fileName,
      String recordLine,
      String lineSeparator
  ) throws Exception {
    Path tempPath = Path.of(config.localTargetDir()).resolve(fileName + config.localTempSuffix());
    if (!Files.exists(tempPath)) {
      throw new IllegalStateException("Streaming temp file is missing before append: " + tempPath);
    }
    Files.writeString(
        tempPath,
        recordLine + lineSeparator,
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND,
        StandardOpenOption.WRITE
    );
  }

  @Override
  public boolean supportsStreaming() {
    return true;
  }

  private void appendManifest(
      ClearingExportWorkerConfig config,
      String fileName,
      int recordCount,
      long bytes,
      java.time.Instant createdAt,
      Path finalPath
  ) throws IOException {
    Path manifest = resolveManifestPath(config);
    Files.createDirectories(manifest.getParent());

    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("fileName", fileName);
    entry.put("recordCount", recordCount);
    entry.put("bytes", bytes);
    entry.put("createdAt", createdAt.toString());
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

  private static boolean endsWith(Path path, String suffix) throws IOException {
    byte[] suffixBytes = suffix.getBytes(StandardCharsets.UTF_8);
    long size = Files.size(path);
    if (size < suffixBytes.length) {
      return false;
    }
    byte[] tail = new byte[suffixBytes.length];
    try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(path.toFile(), "r")) {
      file.seek(size - suffixBytes.length);
      file.readFully(tail);
    }
    return java.util.Arrays.equals(tail, suffixBytes);
  }
}
