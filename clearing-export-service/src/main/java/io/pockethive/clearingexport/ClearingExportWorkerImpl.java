package io.pockethive.clearingexport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime.WorkerStateSnapshot;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import jakarta.annotation.PostConstruct;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
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
  private static final String WORKER_BEAN_NAME = "clearingExportWorker";

  private final ClearingExportWorkerProperties properties;
  private final ClearingExportBatchWriter batchWriter;
  private final ClearingStructuredSchemaRegistry schemaRegistry;
  private final StructuredRecordProjector structuredRecordProjector;
  private final TemplateRenderer templateRenderer;
  private final ObjectMapper objectMapper;
  private final Clock clock;
  private final WorkerControlPlaneRuntime controlPlaneRuntime;
  private final ConfigurableApplicationContext applicationContext;
  private final AtomicBoolean preflightListenerRegistered = new AtomicBoolean(false);
  private final AtomicReference<StatusPublisher> lastStatusPublisher = new AtomicReference<>();
  private final AtomicReference<Boolean> lastEnabled = new AtomicReference<>(true);
  private final AtomicReference<String> fatalReason = new AtomicReference<>();
  private final String lifecycleCorrelationId;

  @Autowired
  ClearingExportWorkerImpl(
      ClearingExportWorkerProperties properties,
      ClearingExportBatchWriter batchWriter,
      ClearingStructuredSchemaRegistry schemaRegistry,
      StructuredRecordProjector structuredRecordProjector,
      TemplateRenderer templateRenderer,
      WorkerControlPlaneRuntime controlPlaneRuntime,
      ConfigurableApplicationContext applicationContext
  ) {
    this(
        properties,
        batchWriter,
        schemaRegistry,
        structuredRecordProjector,
        templateRenderer,
        controlPlaneRuntime,
        applicationContext,
        new ObjectMapper().findAndRegisterModules(),
        Clock.systemUTC());
  }

  ClearingExportWorkerImpl(
      ClearingExportWorkerProperties properties,
      ClearingExportBatchWriter batchWriter,
      ClearingStructuredSchemaRegistry schemaRegistry,
      StructuredRecordProjector structuredRecordProjector,
      TemplateRenderer templateRenderer,
      WorkerControlPlaneRuntime controlPlaneRuntime,
      ConfigurableApplicationContext applicationContext,
      ObjectMapper objectMapper,
      Clock clock
  ) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.batchWriter = Objects.requireNonNull(batchWriter, "batchWriter");
    this.schemaRegistry = Objects.requireNonNull(schemaRegistry, "schemaRegistry");
    this.structuredRecordProjector = Objects.requireNonNull(structuredRecordProjector, "structuredRecordProjector");
    this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
    this.controlPlaneRuntime = Objects.requireNonNull(controlPlaneRuntime, "controlPlaneRuntime");
    this.applicationContext = Objects.requireNonNull(applicationContext, "applicationContext");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.lifecycleCorrelationId = "clearing-export-lifecycle-" + UUID.randomUUID();
  }

  @PostConstruct
  void registerPreflightStateListener() {
    if (!preflightListenerRegistered.compareAndSet(false, true)) {
      return;
    }
    batchWriter.setLifecycleListener(this::publishLifecycleJournalEvent);
    controlPlaneRuntime.registerStateListener(WORKER_BEAN_NAME, this::preflightOrHaltOnStateUpdate);
  }

  @Override
  public WorkItem onMessage(WorkItem in, WorkerContext context) {
    rememberStatusContext(context);
    String fatal = fatalReason.get();
    if (fatal != null) {
      throw new IllegalStateException("Clearing-export worker is halted: " + fatal);
    }

    ClearingExportWorkerConfig config =
        context.configOrDefault(ClearingExportWorkerConfig.class, properties::defaultConfig);

    Map<String, Object> record = projectRecord(in);
    Map<String, Object> renderContext = baseRenderContext(record);

    try {
      if (config.structuredMode()) {
        ClearingStructuredSchema schema = schemaRegistry.resolve(config);
        StructuredProjectedRecord projected = structuredRecordProjector.project(schema, renderContext);
        batchWriter.appendStructured(projected, config, schema);
      } else {
        String renderedLine = templateRenderer.render(config.recordTemplate(), renderContext);
        batchWriter.append(renderedLine, config);
      }
    } catch (Exception ex) {
      publishStatus(context);
      throw new IllegalStateException("Failed to append clearing export record", ex);
    }

    publishStatus(context);
    return null;
  }

  private Map<String, Object> baseRenderContext(Map<String, Object> record) {
    Map<String, Object> renderContext = new LinkedHashMap<>();
    renderContext.put("record", record);
    renderContext.put("now", Instant.now(clock).toString());
    return renderContext;
  }

  @Scheduled(fixedDelayString = "${pockethive.clearing-export.flush-check-ms:250}")
  void flushIfDue() {
    if (fatalReason.get() != null) {
      return;
    }
    try {
      batchWriter.flushIfDue();
      publishStatusFromRuntime();
    } catch (Exception ex) {
      publishRuntimeFailure(ex, "flush");
      log.warn("Periodic clearing-export flush failed: {}", ex.getMessage(), ex);
    }
  }

  private void preflightOrHaltOnStateUpdate(WorkerStateSnapshot snapshot) {
    if (snapshot == null || !snapshot.enabled() || fatalReason.get() != null) {
      return;
    }
    try {
      batchWriter.preflight(resolveConfig(snapshot));
    } catch (Exception ex) {
      String message = "Streaming preflight failed: " + ex.getMessage();
      publishStatusFromRuntimeFailure(message);
      WorkItem synthetic = WorkItem.text("")
          .header("x-ph-call-id", "clearing-export-preflight")
          .build();
      publishJournalAlert(synthetic, ex);
      requestWorkerStop(message);
    }
  }

  private ClearingExportWorkerConfig resolveConfig(WorkerStateSnapshot snapshot) {
    return snapshot.config(ClearingExportWorkerConfig.class)
        .orElseGet(() -> {
          Map<String, Object> raw = snapshot.rawConfig();
          if (raw == null || raw.isEmpty()) {
            return properties.defaultConfig();
          }
          try {
            return objectMapper.convertValue(raw, ClearingExportWorkerConfig.class);
          } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid clearing-export config received from control-plane", ex);
          }
        });
  }

  private void publishStatusFromRuntimeFailure(String message) {
    StatusPublisher statusPublisher = lastStatusPublisher.get();
    if (statusPublisher == null) {
      return;
    }
    publishStatus(statusPublisher, Boolean.TRUE.equals(lastEnabled.get()), message);
  }

  private void publishRuntimeFailure(Exception ex, String phase) {
    String message = "Runtime " + phase + " failure: " + ex.getMessage();
    StatusPublisher statusPublisher = lastStatusPublisher.get();
    if (statusPublisher != null) {
      publishStatus(statusPublisher, Boolean.TRUE.equals(lastEnabled.get()), message);
    }
    WorkItem synthetic = WorkItem.text("")
        .header("x-ph-call-id", "clearing-export-" + phase)
        .build();
    publishJournalAlert(synthetic, ex);
    requestWorkerStop(message);
  }

  private void publishStatus(WorkerContext context) {
    rememberStatusContext(context);
    publishStatus(context.statusPublisher(), context.enabled());
  }

  private void publishStatusFromRuntime() {
    StatusPublisher statusPublisher = lastStatusPublisher.get();
    if (statusPublisher == null) {
      return;
    }
    publishStatus(statusPublisher, lastEnabled.get());
  }

  private void rememberStatusContext(WorkerContext context) {
    lastStatusPublisher.set(context.statusPublisher());
    lastEnabled.set(context.enabled());
  }

  private void publishStatus(StatusPublisher statusPublisher, boolean enabled) {
    publishStatus(statusPublisher, enabled, null);
  }

  private void publishStatus(StatusPublisher statusPublisher, boolean enabled, String fatalMessage) {
    statusPublisher.update(status -> status
        .data("enabled", enabled)
        .data("bufferedRecords", batchWriter.bufferedRecords())
        .data("recordsAccepted", batchWriter.recordsAccepted())
        .data("filesWritten", batchWriter.filesWritten())
        .data("filesFailed", batchWriter.filesFailed())
        .data("lastFileName", batchWriter.lastFileName())
        .data("lastFileRecordCount", batchWriter.lastFileRecordCount())
        .data("lastFileBytes", batchWriter.lastFileBytes())
        .data("lastFlushAt", batchWriter.lastFlushAt() == null ? "" : batchWriter.lastFlushAt().toString())
        .data("lastError", batchWriter.lastError())
        .data("fatalError", fatalMessage == null ? "" : fatalMessage));
  }

  private void publishJournalAlert(WorkItem item, Exception ex) {
    try {
      controlPlaneRuntime.publishWorkError(WORKER_BEAN_NAME, item, ex);
    } catch (Exception publishEx) {
      log.warn("Failed to publish control-plane alert for clearing-export failure", publishEx);
    }
  }

  private void publishLifecycleJournalEvent(ClearingExportBatchWriter.ClearingExportLifecycleEvent event) {
    if (event == null) {
      return;
    }

    String callId = switch (event.type()) {
      case CREATED -> "clearing-export-created";
      case WRITE_FAILED -> "clearing-export-write-failed";
      case FINALIZE_FAILED -> "clearing-export-finalize-failed";
      case FLUSH_SUMMARY -> "clearing-export-flush-summary";
    };

    try {
      controlPlaneRuntime.publishWorkJournalEvent(
          WORKER_BEAN_NAME,
          lifecycleCorrelationId,
          null,
          "work-journal",
          "recorded",
          callId,
          null,
          null,
          lifecycleDetails(event));
    } catch (Exception publishEx) {
      log.warn("Failed to publish clearing-export lifecycle journal event", publishEx);
    }
  }

  private Map<String, Object> lifecycleDetails(ClearingExportBatchWriter.ClearingExportLifecycleEvent event) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("event", event.type().name().toLowerCase());
    ClearingExportSinkWriteResult result = event.sinkResult();
    if (result != null) {
      details.put("fileName", result.fileName());
      details.put("recordCount", result.recordCount());
      details.put("bytes", result.bytes());
    }
    Throwable failure = event.failure();
    if (failure != null) {
      details.put("errorType", failure.getClass().getName());
      details.put("errorMessage", failure.getMessage() == null ? "" : failure.getMessage());
    }
    return details;
  }

  private void requestWorkerStop(String message) {
    if (!fatalReason.compareAndSet(null, message)) {
      return;
    }
    Thread shutdownThread = new Thread(applicationContext::close, "clearing-export-fatal-stop");
    shutdownThread.setDaemon(true);
    shutdownThread.start();
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
