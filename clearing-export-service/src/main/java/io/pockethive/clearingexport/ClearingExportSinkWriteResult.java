package io.pockethive.clearingexport;

import java.time.Instant;
import java.util.Objects;

record ClearingExportSinkWriteResult(
    String fileName,
    int recordCount,
    long bytes,
    Instant createdAt,
    String location
) {
  ClearingExportSinkWriteResult {
    fileName = requireText(fileName, "fileName");
    recordCount = Math.max(0, recordCount);
    bytes = Math.max(0L, bytes);
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    location = requireText(location, "location");
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must be configured");
    }
    return value;
  }
}

