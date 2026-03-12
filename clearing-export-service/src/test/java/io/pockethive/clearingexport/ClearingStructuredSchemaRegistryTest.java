package io.pockethive.clearingexport;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClearingStructuredSchemaRegistryTest {

  private static final String TEST_SCHEMA_ID = "test-schema";
  private static final String TEST_SCHEMA_VERSION = "1.0.0";

  @Test
  void includesSchemaContextAndPathWhenSchemaValidationFails(@TempDir Path tempDir) throws Exception {
    Path schemaDir = tempDir.resolve(TEST_SCHEMA_ID).resolve(TEST_SCHEMA_VERSION);
    Files.createDirectories(schemaDir);
    Path schemaPath = schemaDir.resolve("schema.yaml");
    Files.writeString(schemaPath, """
        schemaId: test-schema
        schemaVersion: "1.0.0"
        outputFormat: xml
        fileNameTemplate: "CLEARING_{{ now }}.xml"
        recordMapping:
          payload:
            expression: "{{ steps.selected.payload }}"
            required: true
            type: string
        xml:
          headerElement: FileHeader
          recordsElement: Transactions
          recordElement: Transaction
          footerElement: FileTrailer
        """);

    ClearingStructuredSchemaRegistry registry = new ClearingStructuredSchemaRegistry();

    assertThatThrownBy(() -> registry.resolve(structuredConfig(tempDir)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TEST_SCHEMA_ID + ":" + TEST_SCHEMA_VERSION)
        .hasMessageContaining(tempDir.toString())
        .hasMessageContaining(schemaPath.toString());
  }

  private static ClearingExportWorkerConfig structuredConfig(Path schemaRegistryRoot) {
    return new ClearingExportWorkerConfig(
        "structured",
        false,
        2_000L,
        1_000,
        1_000,
        50_000,
        true,
        "\n",
        "out.xml",
        "H|{{ now }}",
        "D|{{ steps.selected.payload }}",
        "T|{{ recordCount }}",
        "/tmp/out",
        ".tmp",
        false,
        "reports/clearing/manifest.jsonl",
        schemaRegistryRoot.toString(),
        TEST_SCHEMA_ID,
        TEST_SCHEMA_VERSION
    );
  }
}
