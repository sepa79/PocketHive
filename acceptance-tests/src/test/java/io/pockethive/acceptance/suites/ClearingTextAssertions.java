package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.exports.ExportFilesSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * Responsibility: compare the EX-1 fixture's exact text records, headers and trailers.
 * Must not: render production templates, resolve paths or infer lifecycle outcomes.
 * Contract: RESP-ACCEPTANCE-EXPORT-FILES — docs/architecture/acceptance-tests.md#resp-acceptance-export-files.
 */
final class ClearingTextAssertions {
  private ClearingTextAssertions() { }
  static void requireRecords(ExportFilesSnapshot files, List<String> expected) {
    assertEquals(2, files.finalized().size());
    assertTrue(files.pending().isEmpty());
    var records = new ArrayList<String>();
    files.finalized().forEach((name, content) -> {
      assertTrue(name.endsWith(".txt"), name);
      var lines = content.lines().toList();
      assertEquals(12, lines.size());
      assertEquals("H|acceptance", lines.getFirst());
      assertEquals("T|10", lines.getLast());
      records.addAll(lines.subList(1, 11));
    });
    assertEquals(expected.stream().map(value -> "D|" + value).sorted().toList(), records.stream().sorted().toList());
  }
}
