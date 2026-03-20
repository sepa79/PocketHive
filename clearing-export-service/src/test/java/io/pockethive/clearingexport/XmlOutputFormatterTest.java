package io.pockethive.clearingexport;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class XmlOutputFormatterTest {

  private static final String TEST_SCHEMA_ID = "test-schema";
  private static final String TEST_SCHEMA_VERSION = "1.0.0";

  @Test
  void formatsNestedFieldsFromDotNotation() {
    XmlOutputFormatter formatter = new XmlOutputFormatter();
    ClearingStructuredSchema schema = schema(xmlConfig("", "Transactions", "Transaction"));

    String xml = formatter.format(
        schema,
        Map.of("creationDateTime", "2026-02-20T00:00:00Z"),
        List.of(Map.of("acceptor.name", "SHOP", "amount", "1250")),
        Map.of("recordCount", "1")
    );

    assertThat(xml).contains("<Document>");
    assertThat(xml).contains("<Header><creationDateTime>2026-02-20T00:00:00Z</creationDateTime></Header>");
    assertThat(xml).contains("<Transaction>");
    assertThat(xml).contains("<acceptor><name>SHOP</name></acceptor>");
    assertThat(xml).contains("<amount>1250</amount>");
    assertThat(xml).contains("<Footer><recordCount>1</recordCount></Footer>");
  }

  @Test
  void skipsRecordsWrapperWhenRecordsElementIsBlank() {
    XmlOutputFormatter formatter = new XmlOutputFormatter();

    String xml = formatter.format(
        schema(xmlConfig("", "", "Transaction")),
        Map.of("creationDateTime", "2026-02-20T00:00:00Z"),
        List.of(Map.of("seq", "1")),
        Map.of("recordCount", "1")
    );

    assertThat(xml).doesNotContain("<Transactions>");
    assertThat(xml).contains("<Header><creationDateTime>2026-02-20T00:00:00Z</creationDateTime></Header>");
    assertThat(xml).contains("<Transaction><seq>1</seq></Transaction>");
  }

  @Test
  void inlinesRecordFieldsWhenRecordElementIsBlank() {
    XmlOutputFormatter formatter = new XmlOutputFormatter();

    String xml = formatter.format(
        schema(xmlConfig("", "Transactions", "")),
        Map.of("creationDateTime", "2026-02-20T00:00:00Z"),
        List.of(Map.of("acceptor.name", "SHOP", "amount", "1250")),
        Map.of("recordCount", "1")
    );

    assertThat(xml).contains("<Transactions>");
    assertThat(xml).contains("<acceptor><name>SHOP</name></acceptor>");
    assertThat(xml).contains("<amount>1250</amount>");
    assertThat(xml).doesNotContain("<Transaction>");
  }

  @Test
  void preservesRecordOrderWhenRecordElementIsBlank() {
    XmlOutputFormatter formatter = new XmlOutputFormatter();

    String xml = formatter.format(
        schema(xmlConfig("", "Transactions", "")),
        Map.of(),
        List.of(
            Map.of("seq", "first"),
            Map.of("seq", "second")),
        Map.of()
    );

    assertThat(xml.indexOf("<seq>first</seq>")).isLessThan(xml.indexOf("<seq>second</seq>"));
  }

  @Test
  void wrapsHeaderRecordsAndFooterWhenWrapperElementIsConfigured() {
    XmlOutputFormatter formatter = new XmlOutputFormatter();

    String xml = formatter.format(
        schema(xmlConfig("Batch", "Transactions", "Transaction")),
        Map.of("creationDateTime", "2026-02-20T00:00:00Z"),
        List.of(Map.of("seq", "1")),
        Map.of("recordCount", "1")
    );

    assertThat(xml).contains("<Document><Batch><Header>");
    assertThat(xml).contains("<Transactions><Transaction><seq>1</seq></Transaction></Transactions>");
    assertThat(xml).contains("</Footer></Batch></Document>");
  }

  private static ClearingStructuredSchema schema(ClearingStructuredSchema.XmlOutputConfig xmlConfig) {
    return new ClearingStructuredSchema(
        TEST_SCHEMA_ID,
        TEST_SCHEMA_VERSION,
        "xml",
        "out.xml",
        Map.of("amount", new ClearingStructuredSchema.StructuredFieldRule("{{ steps.selected.json.amount }}", true, "long")),
        Map.of("creationDateTime", "{{ now }}"),
        Map.of("recordCount", "{{ recordCount }}"),
        xmlConfig
    );
  }

  private static ClearingStructuredSchema.XmlOutputConfig xmlConfig(
      String wrapperElement,
      String recordsElement,
      String recordElement
  ) {
    return new ClearingStructuredSchema.XmlOutputConfig(
        true,
        "UTF-8",
        "Document",
        wrapperElement,
        "Header",
        recordsElement,
        recordElement,
        "Footer",
        "",
        "",
        "",
        ""
    );
  }
}
