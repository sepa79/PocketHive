package io.pockethive.clearingexport;

import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredRecordProjectorTest {

  private static final String TEST_SCHEMA_ID = "test-schema";
  private static final String TEST_SCHEMA_VERSION = "1.0.0";

  @Test
  void projectsRequiredAndOptionalFields() {
    StructuredRecordProjector projector = new StructuredRecordProjector(new PebbleTemplateRenderer());
    ClearingStructuredSchema schema = new ClearingStructuredSchema(
        TEST_SCHEMA_ID,
        TEST_SCHEMA_VERSION,
        "xml",
        "out.xml",
        Map.of(
            "amount", new ClearingStructuredSchema.StructuredFieldRule("{{ steps.selected.json.amount }}", true, "long"),
            "optionalField", new ClearingStructuredSchema.StructuredFieldRule("{{ steps.selected.json.missing }}", false, "string")
        ),
        Map.of(),
        Map.of(),
        xmlConfig()
    );

    Map<String, Object> context = Map.of(
        "steps", Map.of("selected", Map.of("json", Map.of("amount", 12))));
    StructuredProjectedRecord record = projector.project(schema, context);
    assertThat(record.values()).containsEntry("amount", "12");
    assertThat(record.values()).doesNotContainKey("optionalField");
    assertThat(record.numericValues()).containsEntry("amount", 12.0);
  }

  @Test
  void failsWhenRequiredFieldIsMissing() {
    StructuredRecordProjector projector = new StructuredRecordProjector(new PebbleTemplateRenderer());
    ClearingStructuredSchema schema = new ClearingStructuredSchema(
        TEST_SCHEMA_ID,
        TEST_SCHEMA_VERSION,
        "xml",
        "out.xml",
        Map.of("amount", new ClearingStructuredSchema.StructuredFieldRule("{{ steps.selected.json.amount }}", true, "long")),
        Map.of(),
        Map.of(),
        xmlConfig()
    );

    Map<String, Object> context = Map.of("steps", Map.of("selected", Map.of("json", Map.of())));
    assertThatThrownBy(() -> projector.project(schema, context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("required field");
  }

  private static ClearingStructuredSchema.XmlOutputConfig xmlConfig() {
    return new ClearingStructuredSchema.XmlOutputConfig(
        true,
        "UTF-8",
        "Document",
        null,
        "Header",
        "Transactions",
        "Transaction",
        "Footer",
        "",
        "",
        "",
        ""
    );
  }
}
