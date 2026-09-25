package io.pockethive.tcpmock.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Responsibility: read and atomically replace one explicitly located snapshot file. Must not:
 * decode JSON, choose defaults or publish application state. Contract:
 * RESP-TCP-MOCK-CATALOGUE-STORAGE —
 * docs/architecture/runtime-responsibilities.md#resp-tcp-mock-catalogue-storage.
 */
final class AtomicSnapshotFile {
  private final Path snapshot;

  AtomicSnapshotFile(Path snapshot) {
    this.snapshot = snapshot;
  }

  boolean exists() {
    return !Files.notExists(snapshot);
  }

  byte[] read() throws IOException {
    return Files.readAllBytes(snapshot);
  }

  void write(byte[] content) {
    Path temporary = null;
    try {
      Files.createDirectories(snapshot.getParent());
      temporary = Files.createTempFile(snapshot.getParent(), ".tcp-snapshot-", ".tmp");
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      Files.move(
          temporary, snapshot, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException cleanupFailure) {
          e.addSuppressed(cleanupFailure);
        }
      }
      throw new UncheckedIOException("Cannot save snapshot: " + snapshot, e);
    }
  }
}
