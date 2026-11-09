package io.pockethive.clearingexport;

import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.templating.sources.ClearingStructuredTemplateSources;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
@Component
/**
 * Responsibility: map and validate configured fields for one structured clearing record.
 * Must not: make rendering helpers persist files or create a second template evaluator.
 * Contract: RESP-CLEARING-EXPORT — docs/architecture/runtime-responsibilities.md#resp-clearing-export.
 */
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

    Map<String, String> expressions = new LinkedHashMap<>();
    schema.recordMapping().forEach((key, rule) -> expressions.put(key, rule.expression()));
    var sources = new ClearingStructuredTemplateSources(
        schema.fileNameTemplate(), schema.headerMapping(), schema.footerMapping(), expressions);
    for (Map.Entry<String, ClearingStructuredSchema.StructuredFieldRule> entry : schema.recordMapping().entrySet()) {
      String fieldName = entry.getKey();
      ClearingStructuredSchema.StructuredFieldRule rule = entry.getValue();
      String rendered = templateRenderer.render(sources.recordExpressions().get(fieldName), context);
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

