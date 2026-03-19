package io.pockethive.clearingexport;

import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClearingExportFileAssemblerTest {

  private static final String TEST_SCHEMA_ID = "test-schema";
  private static final String TEST_SCHEMA_VERSION = "1.0.0";

  @Test
  void assemblesHeaderRecordsAndFooterUsingTemplates() {
    Clock fixed = Clock.fixed(Instant.parse("2026-02-18T10:15:30Z"), ZoneOffset.UTC);
    ClearingExportFileAssembler assembler =
        new ClearingExportFileAssembler(new PebbleTemplateRenderer(), new XmlOutputFormatter(), fixed);

    ClearingExportWorkerConfig config = new ClearingExportWorkerConfig(
        "template",
        false,
        21_600_000L,
        10,
        1_000,
        100,
        true,
        "\n",
        "out_{{ recordCount }}.txt",
        "H|{{ now }}",
        "D|{{ steps.selected.payload }}",
        "T|{{ recordCount }}",
        "/tmp/out",
        ".tmp",
        false,
        "reports/manifest.jsonl",
        "/tmp/schemas",
        null,
        null
    );

    ClearingRenderedFile rendered = assembler.assemble(config, List.of("D|one", "D|two"));

    assertThat(rendered.fileName()).isEqualTo("out_2.txt");
    assertThat(rendered.recordCount()).isEqualTo(2);
    assertThat(rendered.content()).isEqualTo(
        "H|2026-02-18T10:15:30Z\n" +
            "D|one\n" +
            "D|two\n" +
            "T|2\n");
  }

  @Test
  void wrapsStructuredAssemblyFailuresWithSchemaContext() {
    Clock fixed = Clock.fixed(Instant.parse("2026-02-18T10:15:30Z"), ZoneOffset.UTC);
    XmlOutputFormatter formatter = new XmlOutputFormatter() {
      @Override
      String format(
          ClearingStructuredSchema schema,
          Map<String, String> headerValues,
          List<Map<String, String>> records,
          Map<String, String> footerValues
      ) {
        throw new IllegalStateException("formatter failed");
      }
    };
    ClearingExportFileAssembler assembler =
        new ClearingExportFileAssembler(new PebbleTemplateRenderer(), formatter, fixed);

    assertThatThrownBy(() -> assembler.assembleStructured(
        structuredConfig(),
        structuredSchema(),
        List.of(Map.of("payload", "x")),
        Map.of("recordCount", 1)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TEST_SCHEMA_ID + ":" + TEST_SCHEMA_VERSION)
        .hasRootCauseMessage("formatter failed");
  }

  private static ClearingExportWorkerConfig structuredConfig() {
    return new ClearingExportWorkerConfig(
        "structured",
        false,
        21_600_000L,
        10,
        1_000,
        100,
        true,
        "\n",
        "out.xml",
        "H|{{ now }}",
        "D|{{ steps.selected.payload }}",
        "T|{{ recordCount }}",
        "/tmp/out",
        ".tmp",
        false,
        "reports/manifest.jsonl",
        "/tmp/schemas",
        TEST_SCHEMA_ID,
        TEST_SCHEMA_VERSION
    );
  }

  private static ClearingStructuredSchema structuredSchema() {
    return new ClearingStructuredSchema(
        TEST_SCHEMA_ID,
        TEST_SCHEMA_VERSION,
        "xml",
        "out.xml",
        Map.of("payload", new ClearingStructuredSchema.StructuredFieldRule("{{ steps.selected.payload }}", true, "string")),
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
  }
}
