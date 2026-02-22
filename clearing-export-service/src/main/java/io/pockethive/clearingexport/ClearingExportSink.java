package io.pockethive.clearingexport;

interface ClearingExportSink {

  ClearingExportSinkWriteResult writeFile(
      ClearingExportWorkerConfig config,
      ClearingRenderedFile file
  ) throws Exception;

  default ClearingExportSinkWriteResult finalizeStreamingFile(
      ClearingExportWorkerConfig config,
      String fileName,
      String footerLine,
      String lineSeparator,
      int recordCount,
      java.time.Instant createdAt
  ) throws Exception {
    throw new UnsupportedOperationException("Streaming finalize is not supported by this sink");
  }

  default void openStreamingFile(
      ClearingExportWorkerConfig config,
      String fileName,
      String headerLine,
      String lineSeparator
  ) throws Exception {
    throw new UnsupportedOperationException("Streaming open is not supported by this sink");
  }

  default void appendStreamingRecord(
      ClearingExportWorkerConfig config,
      String fileName,
      String recordLine,
      String lineSeparator
  ) throws Exception {
    throw new UnsupportedOperationException("Streaming append is not supported by this sink");
  }

  default boolean supportsStreaming() {
    return false;
  }
}
