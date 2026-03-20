package io.pockethive.clearingexport;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClearingStructuredSchemaTest {

  private static final String TEST_SCHEMA_ID = "test-schema";
  private static final String TEST_SCHEMA_VERSION = "1.0.0";

  @Test
  void failsWhenXmlConfigIsMissing() {
    assertThatThrownBy(() -> schema(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("xml must be configured");
  }

  @Test
  void failsWhenStructuralXmlFieldsAreMissing() {
    assertThatThrownBy(() -> schema(xmlConfig(true, "UTF-8", null, "Header", "Transactions", "Transaction", "Footer")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("xml.rootElement");
    assertThatThrownBy(() -> schema(xmlConfig(true, "UTF-8", "Document", "Header", null, "Transaction", "Footer")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("xml.recordsElement");
    assertThatThrownBy(() -> schema(xmlConfig(true, "UTF-8", "Document", "Header", "Transactions", null, "Footer")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("xml.recordElement");
    assertThatThrownBy(() -> schema(xmlConfig(true, "UTF-8", "Document", null, "Transactions", "Transaction", "Footer")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("xml.headerElement");
    assertThatThrownBy(() -> schema(xmlConfig(true, "UTF-8", "Document", "Header", "Transactions", "Transaction", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("xml.footerElement");
  }

  @Test
  void failsWhenRootHeaderOrFooterAreBlank() {
    assertThatThrownBy(() -> schema(xmlConfig(true, "UTF-8", " ", "Header", "Transactions", "Transaction", "Footer")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("xml.rootElement");
    assertThatThrownBy(() -> schema(xmlConfig(true, "UTF-8", "Document", " ", "Transactions", "Transaction", "Footer")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("xml.headerElement");
    assertThatThrownBy(() -> schema(xmlConfig(true, "UTF-8", "Document", "Header", "Transactions", "Transaction", " ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("xml.footerElement");
  }

  @Test
  void preservesBlankRecordWrappers() {
    ClearingStructuredSchema schema = schema(xmlConfig(true, "UTF-8", "Document", "Header", " ", " ", "Footer"));

    assertThat(schema.xml().recordsElement()).isEmpty();
    assertThat(schema.xml().recordElement()).isEmpty();
  }

  @Test
  void keepsSerializerDefaultsOnly() {
    ClearingStructuredSchema schema = schema(new ClearingStructuredSchema.XmlOutputConfig(
        null,
        null,
        "Document",
        null,
        "Header",
        "Transactions",
        "Transaction",
        "Footer",
        null,
        null,
        null,
        null
    ));

    assertThat(schema.xml().declaration()).isTrue();
    assertThat(schema.xml().encoding()).isEqualTo("UTF-8");
    assertThat(schema.xml().wrapperElement()).isEmpty();
    assertThat(schema.xml().namespaceUri()).isEmpty();
    assertThat(schema.xml().namespacePrefix()).isEmpty();
    assertThat(schema.xml().recordNamespaceUri()).isEmpty();
    assertThat(schema.xml().recordNamespacePrefix()).isEmpty();
  }

  private static ClearingStructuredSchema schema(ClearingStructuredSchema.XmlOutputConfig xmlConfig) {
    return new ClearingStructuredSchema(
        TEST_SCHEMA_ID,
        TEST_SCHEMA_VERSION,
        "xml",
        "out.xml",
        Map.of("payload", new ClearingStructuredSchema.StructuredFieldRule("{{ steps.selected.payload }}", true, "string")),
        Map.of(),
        Map.of(),
        xmlConfig
    );
  }

  private static ClearingStructuredSchema.XmlOutputConfig xmlConfig(
      Boolean declaration,
      String encoding,
      String rootElement,
      String headerElement,
      String recordsElement,
      String recordElement,
      String footerElement
  ) {
    return new ClearingStructuredSchema.XmlOutputConfig(
        declaration,
        encoding,
        rootElement,
        "",
        headerElement,
        recordsElement,
        recordElement,
        footerElement,
        "",
        "",
        "",
        ""
    );
  }
}
