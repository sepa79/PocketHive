package io.pockethive.clearingexport;

import java.time.Instant;

record ClearingRenderedFile(
    String fileName,
    String content,
    int recordCount,
    Instant createdAt
) {

  long bytesUtf8() {
    return content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
  }
}
