package io.pockethive.acceptance.suites;

import static org.junit.jupiter.api.Assertions.*;
import io.pockethive.acceptance.exports.ExportFilesSnapshot;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.xml.sax.InputSource;

/**
 * Responsibility: compare EX-2 XML records and totals with independently authored fixture inputs.
 * Must not: render XML, implement a schema registry or calculate product outcomes.
 * Contract: RESP-ACCEPTANCE-EXPORT-FILES — docs/architecture/acceptance-tests.md#resp-acceptance-export-files.
 */
final class ClearingXmlAssertions {
  private ClearingXmlAssertions() { }
  static void requireRecords(ExportFilesSnapshot files, List<String> expected) throws Exception {
    assertEquals(2, files.finalized().size());
    assertTrue(files.pending().isEmpty());
    var builder = DocumentBuilderFactory.newInstance();
    builder.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    builder.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    builder.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    var xpath = XPathFactory.newInstance().newXPath();
    var observed = new ArrayList<String>();
    for (var file : files.finalized().entrySet()) {
      assertTrue(file.getKey().endsWith(".xml"), file.getKey());
      var document = builder.newDocumentBuilder().parse(new InputSource(new StringReader(file.getValue())));
      assertEquals("Export", document.getDocumentElement().getTagName());
      assertEquals("acceptance-structured", xpath.evaluate("/Export/Header/marker", document));
      assertEquals("10", xpath.evaluate("count(/Export/Records/Record)", document));
      long total = 0;
      for (int i = 1; i <= 10; i++) {
        String record = "/Export/Records/Record[" + i + "]";
        String id = xpath.evaluate(record + "/id", document);
        int inputIndex = expected.indexOf(id);
        assertTrue(inputIndex >= 0, "Unknown output record: " + id);
        long amount = inputIndex + 1;
        assertEquals(Long.toString(amount), xpath.evaluate(record + "/amount", document));
        total += amount;
        observed.add(id);
      }
      assertEquals("10", xpath.evaluate("/Export/Trailer/recordCount", document));
      assertEquals(0, BigDecimal.valueOf(total).compareTo(new BigDecimal(
          xpath.evaluate("/Export/Trailer/totalAmount", document))));
    }
    assertEquals(expected.stream().sorted().toList(), observed.stream().sorted().toList());
  }
}
