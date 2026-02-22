package io.pockethive.clearingexport;

import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredRecordProjectorTest {

  @Test
  void projectsRequiredAndOptionalFields() {
    StructuredRecordProjector projector = new StructuredRecordProjector(new PebbleTemplateRenderer());
    ClearingStructuredSchema schema = new ClearingStructuredSchema(
        "pcs",
        "1.0.0",
        "xml",
        "out.xml",
        Map.of(
            "amount", new ClearingStructuredSchema.StructuredFieldRule("{{ record.json.amount }}", true, "long"),
            "optionalField", new ClearingStructuredSchema.StructuredFieldRule("{{ record.json.missing }}", false, "string")
        ),
        Map.of(),
        Map.of(),
        ClearingStructuredSchema.XmlOutputConfig.defaults()
    );

    Map<String, Object> context = Map.of("record", Map.of("json", Map.of("amount", 12)));
    StructuredProjectedRecord record = projector.project(schema, context);
    assertThat(record.values()).containsEntry("amount", "12");
    assertThat(record.values()).doesNotContainKey("optionalField");
    assertThat(record.numericValues()).containsEntry("amount", 12.0);
  }

  @Test
  void failsWhenRequiredFieldIsMissing() {
    StructuredRecordProjector projector = new StructuredRecordProjector(new PebbleTemplateRenderer());
    ClearingStructuredSchema schema = new ClearingStructuredSchema(
        "pcs",
        "1.0.0",
        "xml",
        "out.xml",
        Map.of("amount", new ClearingStructuredSchema.StructuredFieldRule("{{ record.json.amount }}", true, "long")),
        Map.of(),
        Map.of(),
        ClearingStructuredSchema.XmlOutputConfig.defaults()
    );

    Map<String, Object> context = Map.of("record", Map.of("json", Map.of()));
    assertThatThrownBy(() -> projector.project(schema, context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("required field");
  }
}

