package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.exports.ExportFilesSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * Responsibility: compare the EX-1/EX-3 fixtures' exact text records, headers and trailers.
 * Must not: render production templates, resolve paths or infer lifecycle outcomes.
 * Contract: RESP-ACCEPTANCE-EXPORT-FILES — docs/architecture/acceptance-tests.md#resp-acceptance-export-files.
 */
final class ClearingTextAssertions {
  private ClearingTextAssertions() { }
  static void requireRecords(ExportFilesSnapshot files, List<String> expected) {
    requireRecords(files, expected, 10);
  }
  static void requireStreamingFile(ExportFilesSnapshot files, List<String> expected) {
    requireRecords(files, expected, 20);
  }
  private static void requireRecords(ExportFilesSnapshot files, List<String> expected, int recordsPerFile) {
    assertEquals(20, expected.size(), "Clearing fixture must contain twenty records");
    assertEquals(20 / recordsPerFile, files.finalized().size());
    assertTrue(files.pending().isEmpty());
    var records = new ArrayList<String>();
    files.finalized().forEach((name, content) -> {
      assertTrue(name.endsWith(".txt"), name);
      var lines = content.lines().toList();
      assertEquals(recordsPerFile + 2, lines.size());
      assertEquals("H|acceptance", lines.getFirst());
      assertEquals("T|" + recordsPerFile, lines.getLast());
      records.addAll(lines.subList(1, recordsPerFile + 1));
    });
    assertEquals(expected.stream().map(value -> "D|" + value).sorted().toList(), records.stream().sorted().toList());
  }
}
