package io.pockethive.clearingexport;

import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class StructuredRecordProjector {

  private final TemplateRenderer templateRenderer;

  StructuredRecordProjector(TemplateRenderer templateRenderer) {
    this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
  }

  StructuredProjectedRecord project(
      ClearingStructuredSchema schema,
      Map<String, Object> context
  ) {
    Objects.requireNonNull(schema, "schema");
    Objects.requireNonNull(context, "context");

    Map<String, String> values = new LinkedHashMap<>();
    Map<String, Double> numericValues = new LinkedHashMap<>();

    for (Map.Entry<String, ClearingStructuredSchema.StructuredFieldRule> entry : schema.recordMapping().entrySet()) {
      String fieldName = entry.getKey();
      ClearingStructuredSchema.StructuredFieldRule rule = entry.getValue();
      String rendered = templateRenderer.render(rule.expression(), context);
      String normalized = normalize(rendered);

      if (normalized == null) {
        if (rule.requiredFlag()) {
          throw new IllegalStateException("Structured mapping required field is empty: " + fieldName);
        }
        continue;
      }

      switch (rule.type()) {
        case "long" -> {
          try {
            long parsed = Long.parseLong(normalized);
            values.put(fieldName, Long.toString(parsed));
            numericValues.put(fieldName, (double) parsed);
          } catch (NumberFormatException ex) {
            throw new IllegalStateException(
                "Field '" + fieldName + "' expected long but got '" + normalized + "'", ex);
          }
        }
        case "decimal" -> {
          try {
            double parsed = Double.parseDouble(normalized);
            values.put(fieldName, normalized);
            numericValues.put(fieldName, parsed);
          } catch (NumberFormatException ex) {
            throw new IllegalStateException(
                "Field '" + fieldName + "' expected decimal but got '" + normalized + "'", ex);
          }
        }
        default -> values.put(fieldName, normalized);
      }
    }
    return new StructuredProjectedRecord(Map.copyOf(values), Map.copyOf(numericValues));
  }

  private static String normalize(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}

