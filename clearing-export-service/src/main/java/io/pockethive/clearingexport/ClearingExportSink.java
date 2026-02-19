package io.pockethive.clearingexport;

interface ClearingExportSink {

  void writeFile(ClearingExportWorkerConfig config, ClearingRenderedFile file) throws Exception;
}
