package io.pockethive.clearingexport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.api.PocketHiveWorkerFunction;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkStep;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.PocketHiveWorker;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime.WorkerStateSnapshot;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import jakarta.annotation.PostConstruct;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.util.ArrayList;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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

    ClearingExportWorkerConfig config = context.config(ClearingExportWorkerConfig.class);
    if (config == null) {
      config = properties.defaultConfig();
    }

    try {
      List<WorkStep> steps = collectSteps(in);
      WorkStep selectedStep = selectStep(steps, config);
      Map<String, Object> record = projectStep(selectedStep);
      Map<String, Object> renderContext = baseRenderContext(record, steps, selectedStep);
      if (config.structuredMode()) {
        ClearingStructuredSchema schema = schemaRegistry.resolve(config);
        StructuredProjectedRecord projected = structuredRecordProjector.project(schema, renderContext);
        batchWriter.appendStructured(projected, config, schema);
      } else {
        String renderedLine = templateRenderer.render(config.recordTemplate(), renderContext);
        batchWriter.append(renderedLine, config);
      }
    } catch (Exception ex) {
      handleRecordBuildFailure(in, context, config, ex);
      return null;
    }

    publishStatus(context);
    return null;
  }

  private Map<String, Object> baseRenderContext(
      Map<String, Object> record,
      List<WorkStep> steps,
      WorkStep selectedStep
  ) {
    Map<String, Object> renderContext = new LinkedHashMap<>();
    renderContext.put("record", record);
    renderContext.put("steps", buildStepContext(steps, selectedStep));
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

  private void handleRecordBuildFailure(
      WorkItem item,
      WorkerContext context,
      ClearingExportWorkerConfig config,
      Exception ex
  ) {
    String message = "Failed to append clearing export record (recordSourceStep=" + config.recordSourceStep() + ")";
    switch (config.recordBuildFailurePolicyMode()) {
      case SILENT_DROP -> publishStatus(context);
      case JOURNAL_AND_LOG_ERROR -> {
        log.error(message, ex);
        publishJournalAlert(item, ex);
        publishStatus(context);
      }
      case LOG_ERROR -> {
        log.error(message, ex);
        publishStatus(context);
      }
      case STOP -> {
        log.error(message, ex);
        publishJournalAlert(item, ex);
        publishStatus(context.statusPublisher(), context.enabled(), message);
        requestWorkerStop(message);
      }
    }
  }

  private Map<String, Object> projectStep(WorkStep step) {
    String payload = step.payload();
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("index", step.index());
    record.put("payload", payload);
    record.put("headers", step.headers());

    if (payload != null && !payload.isBlank()) {
      try {
        record.put("json", objectMapper.readValue(payload, MAP_TYPE));
      } catch (Exception ignored) {
        // Payload is not JSON object; templates can still use record.payload.
      }
    }
    return Map.copyOf(record);
  }

  private Map<String, Object> buildStepContext(List<WorkStep> steps, WorkStep selectedStep) {
    List<Map<String, Object>> all = new ArrayList<>(steps.size());
    Map<String, Object> byIndex = new LinkedHashMap<>();
    Map<String, Object> first = null;
    Map<String, Object> latest = null;
    Map<String, Object> previous = null;
    Map<String, Object> selected = null;

    for (int i = 0; i < steps.size(); i++) {
      WorkStep step = steps.get(i);
      Map<String, Object> projected = projectStep(step);
      all.add(projected);
      byIndex.put(Integer.toString(step.index()), projected);
      if (i == 0) {
        first = projected;
      }
      if (i == steps.size() - 1) {
        latest = projected;
      }
      if (i == steps.size() - 2) {
        previous = projected;
      }
      if (step.index() == selectedStep.index()) {
        selected = projected;
      }
    }

    Map<String, Object> context = new LinkedHashMap<>();
    context.put("all", List.copyOf(all));
    context.put("byIndex", Map.copyOf(byIndex));
    context.put("first", first);
    context.put("latest", latest);
    context.put("selected", selected == null ? projectStep(selectedStep) : selected);
    context.put("selectedIndex", selectedStep.index());
    context.put("count", steps.size());
    if (previous != null) {
      context.put("previous", previous);
    }
    return Map.copyOf(context);
  }

  private List<WorkStep> collectSteps(WorkItem item) {
    List<WorkStep> steps = new ArrayList<>();
    for (WorkStep step : item.steps()) {
      if (step != null) {
        steps.add(step);
      }
    }
    if (steps.isEmpty()) {
      throw new IllegalStateException("WorkItem has no steps");
    }
    return steps;
  }

  private WorkStep selectStep(List<WorkStep> steps, ClearingExportWorkerConfig config) {
    return switch (config.sourceStepMode()) {
      case LATEST -> steps.get(steps.size() - 1);
      case FIRST -> steps.get(0);
      case PREVIOUS -> {
        if (steps.size() < 2) {
          throw new IllegalStateException(
              "recordSourceStep=previous requires at least two WorkItem steps");
        }
        yield steps.get(steps.size() - 2);
      }
      case INDEX -> selectStepByIndex(steps, config.recordSourceStepIndex());
    };
  }

  private WorkStep selectStepByIndex(List<WorkStep> steps, int index) {
    for (WorkStep step : steps) {
      if (step.index() == index) {
        return step;
      }
    }
    throw new IllegalStateException("recordSourceStepIndex=" + index + " not found in WorkItem steps");
  }
}
