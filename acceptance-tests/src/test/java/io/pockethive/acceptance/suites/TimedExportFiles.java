package io.pockethive.acceptance.suites;

import io.pockethive.acceptance.exports.ExportFilesSnapshot;

/** A read-only file observation timestamped by the sampling thread, before START returns if necessary. */
record TimedExportFiles(long elapsedSinceStartRequestMs, ExportFilesSnapshot files) { }
