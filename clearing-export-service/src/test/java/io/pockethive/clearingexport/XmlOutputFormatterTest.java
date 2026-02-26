package io.pockethive.clearingexport;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class XmlOutputFormatterTest {

  @Test
  void formatsNestedFieldsFromDotNotation() {
    XmlOutputFormatter formatter = new XmlOutputFormatter();
    ClearingStructuredSchema schema = new ClearingStructuredSchema(
        "pcs",
        "1.0.0",
        "xml",
        "out.xml",
        Map.of("amount", new ClearingStructuredSchema.StructuredFieldRule("{{ steps.selected.json.amount }}", true, "long")),
        Map.of("creationDateTime", "{{ now }}"),
        Map.of("recordCount", "{{ recordCount }}"),
        new ClearingStructuredSchema.XmlOutputConfig(
            true,
            "UTF-8",
            "Document",
            "Header",
            "Transactions",
            "Transaction",
            "Footer",
            "",
            "",
            "",
            "",
            false
        )
    );

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
}
