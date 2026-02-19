package io.pockethive.clearingexport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component("clearingExportWorker")
@PocketHiveWorker(
    capabilities = {WorkerCapability.MESSAGE_DRIVEN},
    config = ClearingExportWorkerConfig.class
)
class ClearingExportWorkerImpl implements PocketHiveWorkerFunction {

  private static final Logger log = LoggerFactory.getLogger(ClearingExportWorkerImpl.class);
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final ClearingExportWorkerProperties properties;
  private final ClearingExportBatchWriter batchWriter;
  private final TemplateRenderer templateRenderer;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @Autowired
  ClearingExportWorkerImpl(
      ClearingExportWorkerProperties properties,
      ClearingExportBatchWriter batchWriter,
      TemplateRenderer templateRenderer
  ) {
    this(
        properties,
        batchWriter,
        templateRenderer,
        new ObjectMapper().findAndRegisterModules(),
        Clock.systemUTC());
  }

  ClearingExportWorkerImpl(
      ClearingExportWorkerProperties properties,
      ClearingExportBatchWriter batchWriter,
      TemplateRenderer templateRenderer,
      ObjectMapper objectMapper,
      Clock clock
  ) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.batchWriter = Objects.requireNonNull(batchWriter, "batchWriter");
    this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public WorkItem onMessage(WorkItem in, WorkerContext context) {
    ClearingExportWorkerConfig config =
        context.configOrDefault(ClearingExportWorkerConfig.class, properties::defaultConfig);

    Map<String, Object> record = projectRecord(in);
    Map<String, Object> renderContext = new LinkedHashMap<>();
    renderContext.put("record", record);
    renderContext.put("now", Instant.now(clock).toString());

    String renderedLine = templateRenderer.render(config.recordTemplate(), renderContext);

    try {
      batchWriter.append(renderedLine, config);
    } catch (Exception ex) {
      publishStatus(context);
      throw new IllegalStateException("Failed to append clearing export record", ex);
    }

    publishStatus(context);
    return null;
  }

  @Scheduled(fixedDelayString = "${pockethive.clearing-export.flush-check-ms:250}")
  void flushIfDue() {
    try {
      batchWriter.flushIfDue();
    } catch (Exception ex) {
      log.warn("Periodic clearing-export flush failed: {}", ex.getMessage(), ex);
    }
  }

  private void publishStatus(WorkerContext context) {
    context.statusPublisher().update(status -> status
        .data("enabled", context.enabled())
        .data("bufferedRecords", batchWriter.bufferedRecords())
        .data("recordsAccepted", batchWriter.recordsAccepted())
        .data("filesWritten", batchWriter.filesWritten())
        .data("filesFailed", batchWriter.filesFailed())
        .data("lastFileName", batchWriter.lastFileName())
        .data("lastFileRecordCount", batchWriter.lastFileRecordCount())
        .data("lastFileBytes", batchWriter.lastFileBytes())
        .data("lastFlushAt", batchWriter.lastFlushAt() == null ? "" : batchWriter.lastFlushAt().toString())
        .data("lastError", batchWriter.lastError()));
  }

  private Map<String, Object> projectRecord(WorkItem item) {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("payload", item.payload());
    record.put("headers", item.headers());

    String payload = item.payload();
    if (payload != null && !payload.isBlank()) {
      try {
        record.put("json", objectMapper.readValue(payload, MAP_TYPE));
      } catch (Exception ignored) {
        // Payload is not JSON object; templates can still use record.payload.
      }
    }
    return record;
  }
}
