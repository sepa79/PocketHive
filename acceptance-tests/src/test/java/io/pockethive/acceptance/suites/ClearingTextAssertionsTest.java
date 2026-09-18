package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.exports.ExportFilesSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ClearingTextAssertionsTest {
  private final List<String> records = IntStream.range(0, 20).mapToObj(i -> "record-" + i).toList();
  private String file(List<String> values) {
    return "H|acceptance\n" + String.join("\n", values.stream().map(v -> "D|" + v).toList()) + "\nT|10\n";
  }
  @Test void acceptsExactUnionRegardlessOfFileOrdering() {
    var files = Map.of("second.txt", file(records.subList(0, 10)), "first.txt", file(records.subList(10, 20)));
    ClearingTextAssertions.requireRecords(new ExportFilesSnapshot(files, Set.of()), records);
  }
  @Test void detectsRepeatedRecordMissingRecordWrongFooterAndPendingOutput() {
    String first = file(records.subList(0, 10));
    String second = file(records.subList(10, 20));
    for (String corrupt : List.of(second.replace("D|record-19", "D|record-18"),
        second.replace("D|record-19\n", ""), second.replace("T|10", "T|9"))) {
      var files = new ExportFilesSnapshot(Map.of("one.txt", first, "two.txt", corrupt), Set.of());
      assertThrows(AssertionError.class, () -> ClearingTextAssertions.requireRecords(files, records));
    }
    var pending = new ExportFilesSnapshot(Map.of("one.txt", first, "two.txt", second), Set.of("third.txt.tmp"));
    assertThrows(AssertionError.class, () -> ClearingTextAssertions.requireRecords(pending, records));
  }
}
