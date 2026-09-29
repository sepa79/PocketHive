package io.pockethive.acceptance.exports;

import java.util.Map;
import java.util.Set;

/**
 * Responsibility: retain an immutable observation of finalized content and pending file names.
 * Must not: infer completion, validate export formats or own cleanup.
 * Contract: RESP-ACCEPTANCE-EXPORT-FILES — docs/architecture/acceptance-tests.md#resp-acceptance-export-files.
 */
public record ExportFilesSnapshot(Map<String, String> finalized, Set<String> pending) {
  public ExportFilesSnapshot {
    finalized = Map.copyOf(finalized);
    pending = Set.copyOf(pending);
  }
}
