package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.exports.ExportFilesSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ClearingXmlAssertionsTest {
  private final List<String> ids = IntStream.rangeClosed(1, 20).mapToObj(i -> "record-" + i + "<&>").toList();
  private String file(int first, int last) {
    String records = IntStream.rangeClosed(first, last).mapToObj(i ->
        "<Record><id>record-" + i + "&lt;&amp;&gt;</id><amount>" + i + "</amount></Record>")
        .collect(java.util.stream.Collectors.joining());
    return "<Export><Header><marker>acceptance-structured</marker></Header><Records>" + records
        + "</Records><Trailer><recordCount>10</recordCount><totalAmount>"
        + IntStream.rangeClosed(first, last).sum() + ".0</totalAmount></Trailer></Export>";
  }
  @Test void verifiesEscapedIdsAmountsAndPerFileTotals() throws Exception {
    ClearingXmlAssertions.requireRecords(new ExportFilesSnapshot(Map.of("one.xml", file(1, 10),
        "two.xml", file(11, 20)), Set.of()), ids);
  }
  @Test void rejectsWrongAmountsTotalsCountsDuplicateAndUnknownRecords() {
    String correct = file(11, 20);
    for (String corrupt : List.of(correct.replace("<amount>20</amount>", "<amount>21</amount>"),
        correct.replace("155.0", "154.0"), correct.replace("<recordCount>10", "<recordCount>9"),
        correct.replace("record-20", "record-19"), correct.replace("record-20", "foreign"))) {
      var files = new ExportFilesSnapshot(Map.of("one.xml", file(1, 10), "two.xml", corrupt), Set.of());
      assertThrows(AssertionError.class, () -> ClearingXmlAssertions.requireRecords(files, ids));
    }
  }
}
