package io.pockethive.clearingexport;

import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class ClearingExportFileAssembler {

  private final TemplateRenderer templateRenderer;
  private final XmlOutputFormatter xmlOutputFormatter;
  private final Clock clock;

  @Autowired
  ClearingExportFileAssembler(TemplateRenderer templateRenderer, XmlOutputFormatter xmlOutputFormatter) {
    this(templateRenderer, xmlOutputFormatter, Clock.systemUTC());
  }

  ClearingExportFileAssembler(TemplateRenderer templateRenderer, XmlOutputFormatter xmlOutputFormatter, Clock clock) {
    this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    this.xmlOutputFormatter = Objects.requireNonNull(xmlOutputFormatter, "xmlOutputFormatter");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  ClearingRenderedFile assemble(ClearingExportWorkerConfig config, List<String> recordLines) {
    Instant now = clock.instant();
    int recordCount = recordLines.size();

    Map<String, Object> baseContext = new LinkedHashMap<>();
    baseContext.put("now", now.toString());
    baseContext.put("recordCount", recordCount);
    baseContext.put("totals", Map.of("recordCount", recordCount));

    String header = templateRenderer.render(config.headerTemplate(), baseContext);
    String footer = templateRenderer.render(config.footerTemplate(), baseContext);
    String fileName = templateRenderer.render(config.fileNameTemplate(), baseContext);

    String separator = config.lineSeparator();
    StringBuilder payload = new StringBuilder(Math.max(128, recordLines.size() * 64));
    payload.append(header).append(separator);
    for (String line : recordLines) {
      payload.append(line).append(separator);
    }
    payload.append(footer).append(separator);

    return new ClearingRenderedFile(fileName, payload.toString(), recordCount, now);
  }

  ClearingRenderedFile assembleStructured(
      ClearingExportWorkerConfig config,
      ClearingStructuredSchema schema,
      List<Map<String, String>> records,
      Map<String, Object> totals
  ) {
    Instant now = clock.instant();
    int recordCount = records.size();

    Map<String, Object> baseContext = new LinkedHashMap<>();
    baseContext.put("now", now.toString());
    baseContext.put("recordCount", recordCount);
    baseContext.put("totals", totals);

    Map<String, String> header = renderMapping(schema.headerMapping(), baseContext);
    Map<String, String> footer = renderMapping(schema.footerMapping(), baseContext);
    String fileName = templateRenderer.render(schema.fileNameTemplate(), baseContext);
    String payload = xmlOutputFormatter.format(schema, header, records, footer);
    return new ClearingRenderedFile(fileName, payload, recordCount, now);
  }

  private Map<String, String> renderMapping(Map<String, String> mapping, Map<String, Object> context) {
    if (mapping == null || mapping.isEmpty()) {
      return Map.of();
    }
    List<Map.Entry<String, String>> entries = new ArrayList<>(mapping.entrySet());
    Map<String, String> rendered = new LinkedHashMap<>(entries.size());
    for (Map.Entry<String, String> entry : entries) {
      rendered.put(entry.getKey(), templateRenderer.render(entry.getValue(), context));
    }
    return Map.copyOf(rendered);
  }
}
