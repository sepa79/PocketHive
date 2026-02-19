package io.pockethive.clearingexport;

import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class ClearingExportFileAssembler {

  private final TemplateRenderer templateRenderer;
  private final Clock clock;

  @Autowired
  ClearingExportFileAssembler(TemplateRenderer templateRenderer) {
    this(templateRenderer, Clock.systemUTC());
  }

  ClearingExportFileAssembler(TemplateRenderer templateRenderer, Clock clock) {
    this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
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
}
