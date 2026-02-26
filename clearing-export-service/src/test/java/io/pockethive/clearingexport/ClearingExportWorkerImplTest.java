package io.pockethive.clearingexport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pockethive.worker.sdk.api.StatusPublisher;
import io.pockethive.worker.sdk.api.WorkItem;
import io.pockethive.worker.sdk.api.WorkerContext;
import io.pockethive.worker.sdk.config.WorkInputConfig;
import io.pockethive.worker.sdk.config.WorkOutputConfig;
import io.pockethive.worker.sdk.config.WorkerCapability;
import io.pockethive.worker.sdk.config.WorkerInputType;
import io.pockethive.worker.sdk.config.WorkerOutputType;
import io.pockethive.worker.sdk.runtime.WorkIoBindings;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime.WorkerStateSnapshot;
import io.pockethive.worker.sdk.runtime.WorkerDefinition;
import io.pockethive.worker.sdk.runtime.WorkerState;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClearingExportWorkerImplTest {

  @Test
  void stateListenerPreflightFailurePublishesJournalAndStopsWorker() throws Exception {
    ClearingExportWorkerProperties properties = mock(ClearingExportWorkerProperties.class);
    ClearingExportBatchWriter batchWriter = mock(ClearingExportBatchWriter.class);
    ClearingStructuredSchemaRegistry schemaRegistry = mock(ClearingStructuredSchemaRegistry.class);
    StructuredRecordProjector projector = mock(StructuredRecordProjector.class);
    TemplateRenderer templateRenderer = mock(TemplateRenderer.class);
    WorkerControlPlaneRuntime controlPlaneRuntime = mock(WorkerControlPlaneRuntime.class);
    ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);
    ClearingExportWorkerConfig config = testConfig();

    when(properties.defaultConfig()).thenReturn(config);
    when(batchWriter.bufferedRecords()).thenReturn(0L);
    when(batchWriter.recordsAccepted()).thenReturn(0L);
    when(batchWriter.filesWritten()).thenReturn(0L);
    when(batchWriter.filesFailed()).thenReturn(0L);
    when(batchWriter.lastFileName()).thenReturn("");
    when(batchWriter.lastFileRecordCount()).thenReturn(0L);
    when(batchWriter.lastFileBytes()).thenReturn(0L);
    when(batchWriter.lastFlushAt()).thenReturn(null);
    when(batchWriter.lastError()).thenReturn("");
    org.mockito.Mockito.doThrow(new IllegalStateException("sink does not support streaming"))
        .when(batchWriter).preflight(config);

    ClearingExportWorkerImpl worker = new ClearingExportWorkerImpl(
        properties,
        batchWriter,
        schemaRegistry,
        projector,
        templateRenderer,
        controlPlaneRuntime,
        applicationContext,
        new ObjectMapper().findAndRegisterModules(),
        Clock.fixed(Instant.parse("2026-02-21T00:00:00Z"), ZoneOffset.UTC));

    @SuppressWarnings("unchecked")
    org.mockito.ArgumentCaptor<Consumer<WorkerStateSnapshot>> listenerCaptor =
        org.mockito.ArgumentCaptor.forClass(Consumer.class);
    worker.registerPreflightStateListener();
    verify(controlPlaneRuntime).registerStateListener(eq("clearingExportWorker"), listenerCaptor.capture());

    listenerCaptor.getValue().accept(buildSnapshot(config, true));

    verify(batchWriter).preflight(config);
    verify(controlPlaneRuntime).publishWorkError(
        eq("clearingExportWorker"),
        argThat(item -> "clearing-export-preflight".equals(String.valueOf(item.headers().get("x-ph-call-id")))),
        any(Exception.class));
    verify(applicationContext, timeout(500)).close();
  }

  @Test
  void onMessageUsesConfiguredFirstStepForRecordTemplateContext() throws Exception {
    ClearingExportWorkerProperties properties = mock(ClearingExportWorkerProperties.class);
    ClearingExportBatchWriter batchWriter = mock(ClearingExportBatchWriter.class);
    ClearingStructuredSchemaRegistry schemaRegistry = mock(ClearingStructuredSchemaRegistry.class);
    StructuredRecordProjector projector = mock(StructuredRecordProjector.class);
    TemplateRenderer templateRenderer = mock(TemplateRenderer.class);
    WorkerControlPlaneRuntime controlPlaneRuntime = mock(WorkerControlPlaneRuntime.class);
    ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);
    WorkerContext context = mock(WorkerContext.class);
    ClearingExportWorkerConfig config = testConfigWith("first", -1, "log_error");

    when(context.enabled()).thenReturn(true);
    when(context.statusPublisher()).thenReturn(StatusPublisher.NO_OP);
    when(context.config(ClearingExportWorkerConfig.class)).thenReturn(config);
    when(templateRenderer.render(eq(config.recordTemplate()), anyMap())).thenReturn("D|x");

    ClearingExportWorkerImpl worker = new ClearingExportWorkerImpl(
        properties,
        batchWriter,
        schemaRegistry,
        projector,
        templateRenderer,
        controlPlaneRuntime,
        applicationContext,
        new ObjectMapper().findAndRegisterModules(),
        Clock.fixed(Instant.parse("2026-02-21T00:00:00Z"), ZoneOffset.UTC));

    WorkItem input = WorkItem.text("{\"id\":1}")
        .header("step", "first")
        .build()
        .addStep("{\"id\":2}", Map.of("step", "latest"));

    worker.onMessage(input, context);

    verify(templateRenderer).render(eq(config.recordTemplate()), argThat(renderContext -> {
      if (renderContext.containsKey("record")) {
        return false;
      }
      if (!(renderContext.get("steps") instanceof Map<?, ?> steps)) {
        return false;
      }
      return (steps.get("selected") instanceof Map<?, ?> selected)
          && "{\"id\":1}".equals(selected.get("payload"))
          && (selected.get("headers") instanceof Map<?, ?> headers)
          && "first".equals(String.valueOf(headers.get("step")));
    }));
    verify(batchWriter).append("D|x", config);
    verify(batchWriter, never()).preflight(any(ClearingExportWorkerConfig.class));
  }

  @Test
  void onMessageUsesConfiguredPreviousStepForRecordTemplateContext() throws Exception {
    ClearingExportWorkerProperties properties = mock(ClearingExportWorkerProperties.class);
    ClearingExportBatchWriter batchWriter = mock(ClearingExportBatchWriter.class);
    ClearingStructuredSchemaRegistry schemaRegistry = mock(ClearingStructuredSchemaRegistry.class);
    StructuredRecordProjector projector = mock(StructuredRecordProjector.class);
    TemplateRenderer templateRenderer = mock(TemplateRenderer.class);
    WorkerControlPlaneRuntime controlPlaneRuntime = mock(WorkerControlPlaneRuntime.class);
    ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);
    WorkerContext context = mock(WorkerContext.class);
    ClearingExportWorkerConfig config = testConfigWith("previous", -1, "log_error");

    when(context.enabled()).thenReturn(true);
    when(context.statusPublisher()).thenReturn(StatusPublisher.NO_OP);
    when(context.config(ClearingExportWorkerConfig.class)).thenReturn(config);
    when(templateRenderer.render(eq(config.recordTemplate()), anyMap())).thenReturn("D|x");

    ClearingExportWorkerImpl worker = new ClearingExportWorkerImpl(
        properties,
        batchWriter,
        schemaRegistry,
        projector,
        templateRenderer,
        controlPlaneRuntime,
        applicationContext,
        new ObjectMapper().findAndRegisterModules(),
        Clock.fixed(Instant.parse("2026-02-21T00:00:00Z"), ZoneOffset.UTC));

    WorkItem input = WorkItem.text("{\"id\":1}")
        .header("step", "first")
        .build()
        .addStep("{\"id\":2}", Map.of("step", "previous"))
        .addStep("{\"id\":3}", Map.of("step", "latest"));

    worker.onMessage(input, context);

    verify(templateRenderer).render(eq(config.recordTemplate()), argThat(renderContext -> {
      if (renderContext.containsKey("record")) {
        return false;
      }
      if (!(renderContext.get("steps") instanceof Map<?, ?> steps)) {
        return false;
      }
      return (steps.get("selected") instanceof Map<?, ?> selected)
          && "{\"id\":2}".equals(selected.get("payload"))
          && (selected.get("headers") instanceof Map<?, ?> headers)
          && "previous".equals(String.valueOf(headers.get("step")));
    }));
    verify(batchWriter).append("D|x", config);
  }

  @Test
  void onMessageUsesConfiguredIndexedStepForRecordTemplateContext() throws Exception {
    ClearingExportWorkerProperties properties = mock(ClearingExportWorkerProperties.class);
    ClearingExportBatchWriter batchWriter = mock(ClearingExportBatchWriter.class);
    ClearingStructuredSchemaRegistry schemaRegistry = mock(ClearingStructuredSchemaRegistry.class);
    StructuredRecordProjector projector = mock(StructuredRecordProjector.class);
    TemplateRenderer templateRenderer = mock(TemplateRenderer.class);
    WorkerControlPlaneRuntime controlPlaneRuntime = mock(WorkerControlPlaneRuntime.class);
    ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);
    WorkerContext context = mock(WorkerContext.class);
    ClearingExportWorkerConfig config = testConfigWith("index", 1, "log_error");

    when(context.enabled()).thenReturn(true);
    when(context.statusPublisher()).thenReturn(StatusPublisher.NO_OP);
    when(context.config(ClearingExportWorkerConfig.class)).thenReturn(config);
    when(templateRenderer.render(eq(config.recordTemplate()), anyMap())).thenReturn("D|x");

    ClearingExportWorkerImpl worker = new ClearingExportWorkerImpl(
        properties,
        batchWriter,
        schemaRegistry,
        projector,
        templateRenderer,
        controlPlaneRuntime,
        applicationContext,
        new ObjectMapper().findAndRegisterModules(),
        Clock.fixed(Instant.parse("2026-02-21T00:00:00Z"), ZoneOffset.UTC));

    WorkItem input = WorkItem.text("{\"id\":1}")
        .header("step", "first")
        .build()
        .addStep("{\"id\":2}", Map.of("step", "index-1"))
        .addStep("{\"id\":3}", Map.of("step", "latest"));

    worker.onMessage(input, context);

    verify(templateRenderer).render(eq(config.recordTemplate()), argThat(renderContext -> {
      if (renderContext.containsKey("record")) {
        return false;
      }
      if (!(renderContext.get("steps") instanceof Map<?, ?> steps)) {
        return false;
      }
      return (steps.get("selected") instanceof Map<?, ?> selected)
          && "{\"id\":2}".equals(selected.get("payload"))
          && (selected.get("headers") instanceof Map<?, ?> headers)
          && "index-1".equals(String.valueOf(headers.get("step")));
    }));
    verify(batchWriter).append("D|x", config);
  }

  @Test
  void onMessageExposesMultiStepContextForTemplateMixing() throws Exception {
    ClearingExportWorkerProperties properties = mock(ClearingExportWorkerProperties.class);
    ClearingExportBatchWriter batchWriter = mock(ClearingExportBatchWriter.class);
    ClearingStructuredSchemaRegistry schemaRegistry = mock(ClearingStructuredSchemaRegistry.class);
    StructuredRecordProjector projector = mock(StructuredRecordProjector.class);
    TemplateRenderer templateRenderer = mock(TemplateRenderer.class);
    WorkerControlPlaneRuntime controlPlaneRuntime = mock(WorkerControlPlaneRuntime.class);
    ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);
    WorkerContext context = mock(WorkerContext.class);
    ClearingExportWorkerConfig config = testConfigWith("latest", -1, "log_error");

    when(context.enabled()).thenReturn(true);
    when(context.statusPublisher()).thenReturn(StatusPublisher.NO_OP);
    when(context.config(ClearingExportWorkerConfig.class)).thenReturn(config);
    when(templateRenderer.render(eq(config.recordTemplate()), anyMap())).thenReturn("D|x");

    ClearingExportWorkerImpl worker = new ClearingExportWorkerImpl(
        properties,
        batchWriter,
        schemaRegistry,
        projector,
        templateRenderer,
        controlPlaneRuntime,
        applicationContext,
        new ObjectMapper().findAndRegisterModules(),
        Clock.fixed(Instant.parse("2026-02-21T00:00:00Z"), ZoneOffset.UTC));

    WorkItem input = WorkItem.text("{\"phase\":\"request\",\"amountMinor\":1250}")
        .header("step", "request")
        .build()
        .addStep("{\"phase\":\"response\",\"responseCode\":\"00\"}", Map.of("step", "response"));

    worker.onMessage(input, context);

    verify(templateRenderer).render(eq(config.recordTemplate()), argThat(
        ClearingExportWorkerImplTest::hasExpectedMixedStepContext));
    verify(batchWriter).append("D|x", config);
  }

  @Test
  void onMessageExposesMultiStepContextForStructuredMapping() throws Exception {
    ClearingExportWorkerProperties properties = mock(ClearingExportWorkerProperties.class);
    ClearingExportBatchWriter batchWriter = mock(ClearingExportBatchWriter.class);
    ClearingStructuredSchemaRegistry schemaRegistry = mock(ClearingStructuredSchemaRegistry.class);
    StructuredRecordProjector projector = mock(StructuredRecordProjector.class);
    TemplateRenderer templateRenderer = mock(TemplateRenderer.class);
    WorkerControlPlaneRuntime controlPlaneRuntime = mock(WorkerControlPlaneRuntime.class);
    ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);
    WorkerContext context = mock(WorkerContext.class);
    ClearingExportWorkerConfig config = structuredConfigWith("latest", -1, "log_error");
    ClearingStructuredSchema schema = new ClearingStructuredSchema(
        "pcs",
        "1.0.0",
        "xml",
        "out.xml",
        Map.of("payload", new ClearingStructuredSchema.StructuredFieldRule("{{ steps.selected.payload }}", true, "string")),
        Map.of(),
        Map.of(),
        ClearingStructuredSchema.XmlOutputConfig.defaults()
    );
    StructuredProjectedRecord projected = new StructuredProjectedRecord(Map.of("payload", "x"), Map.of());

    when(context.enabled()).thenReturn(true);
    when(context.statusPublisher()).thenReturn(StatusPublisher.NO_OP);
    when(context.config(ClearingExportWorkerConfig.class)).thenReturn(config);
    when(schemaRegistry.resolve(config)).thenReturn(schema);
    when(projector.project(eq(schema), anyMap())).thenReturn(projected);

    ClearingExportWorkerImpl worker = new ClearingExportWorkerImpl(
        properties,
        batchWriter,
        schemaRegistry,
        projector,
        templateRenderer,
        controlPlaneRuntime,
        applicationContext,
        new ObjectMapper().findAndRegisterModules(),
        Clock.fixed(Instant.parse("2026-02-21T00:00:00Z"), ZoneOffset.UTC));

    WorkItem input = WorkItem.text("{\"phase\":\"request\",\"amountMinor\":1250}")
        .header("step", "request")
        .build()
        .addStep("{\"phase\":\"response\",\"responseCode\":\"00\"}", Map.of("step", "response"));

    worker.onMessage(input, context);

    verify(schemaRegistry).resolve(config);
    verify(projector).project(eq(schema), argThat(ClearingExportWorkerImplTest::hasExpectedMixedStepContext));
    verify(batchWriter).appendStructured(projected, config, schema);
    verify(templateRenderer, never()).render(anyString(), anyMap());
  }

  @Test
  void onMessageFailureWhenPreviousStepMissingWithStopPolicyPublishesWorkErrorAndStopsWorker() throws Exception {
    ClearingExportWorkerConfig config = testConfigWith("previous", -1, "stop");
    WorkItem item = WorkItem.text("{\"id\":1}").build();
    runStepSelectionFailureScenario(config, item);
  }

  @Test
  void onMessageFailureWhenIndexedStepMissingWithStopPolicyPublishesWorkErrorAndStopsWorker() throws Exception {
    ClearingExportWorkerConfig config = testConfigWith("index", 7, "stop");
    WorkItem item = WorkItem.text("{\"id\":1}").build().addStep("{\"id\":2}", Map.of());
    runStepSelectionFailureScenario(config, item);
  }

  @Test
  void onMessageFailureWithSilentDropPolicyDoesNotPublishManualWorkError() throws Exception {
    runOnMessageFailurePolicyScenario(testConfigWith("latest", -1, "silent_drop"), false, false);
  }

  @Test
  void onMessageFailureWithJournalAndLogPolicyPublishesManualWorkError() throws Exception {
    runOnMessageFailurePolicyScenario(testConfigWith("latest", -1, "journal_and_log_error"), true, false);
  }

  @Test
  void onMessageFailureWithLogOnlyPolicyDoesNotPublishManualWorkError() throws Exception {
    runOnMessageFailurePolicyScenario(testConfigWith("latest", -1, "log_error"), false, false);
  }

  @Test
  void onMessageFailureWithStopPolicyPublishesManualWorkErrorAndStopsWorker() throws Exception {
    runOnMessageFailurePolicyScenario(testConfigWith("latest", -1, "stop"), true, true);
  }

  private void runOnMessageFailurePolicyScenario(
      ClearingExportWorkerConfig config,
      boolean expectWorkError,
      boolean expectStop
  ) throws Exception {
    ClearingExportWorkerProperties properties = mock(ClearingExportWorkerProperties.class);
    ClearingExportBatchWriter batchWriter = mock(ClearingExportBatchWriter.class);
    ClearingStructuredSchemaRegistry schemaRegistry = mock(ClearingStructuredSchemaRegistry.class);
    StructuredRecordProjector projector = mock(StructuredRecordProjector.class);
    TemplateRenderer templateRenderer = mock(TemplateRenderer.class);
    WorkerControlPlaneRuntime controlPlaneRuntime = mock(WorkerControlPlaneRuntime.class);
    ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);
    WorkerContext context = mock(WorkerContext.class);

    when(context.enabled()).thenReturn(true);
    when(context.statusPublisher()).thenReturn(StatusPublisher.NO_OP);
    when(context.config(ClearingExportWorkerConfig.class)).thenReturn(config);
    when(templateRenderer.render(eq(config.recordTemplate()), anyMap())).thenReturn("D|x");
    org.mockito.Mockito.doThrow(new IllegalStateException("write failed"))
        .when(batchWriter).append(eq("D|x"), eq(config));

    ClearingExportWorkerImpl worker = new ClearingExportWorkerImpl(
        properties,
        batchWriter,
        schemaRegistry,
        projector,
        templateRenderer,
        controlPlaneRuntime,
        applicationContext,
        new ObjectMapper().findAndRegisterModules(),
        Clock.fixed(Instant.parse("2026-02-21T00:00:00Z"), ZoneOffset.UTC));

    worker.onMessage(WorkItem.text("{\"id\":1}").build(), context);

    verify(batchWriter, never()).preflight(any(ClearingExportWorkerConfig.class));
    if (expectWorkError) {
      verify(controlPlaneRuntime).publishWorkError(anyString(), any(WorkItem.class), any(Exception.class));
    } else {
      verify(controlPlaneRuntime, never()).publishWorkError(anyString(), any(WorkItem.class), any(Throwable.class));
    }
    if (expectStop) {
      verify(applicationContext, timeout(500)).close();
    } else {
      verify(applicationContext, never()).close();
    }
  }

  private void runStepSelectionFailureScenario(
      ClearingExportWorkerConfig config,
      WorkItem item
  ) throws Exception {
    ClearingExportWorkerProperties properties = mock(ClearingExportWorkerProperties.class);
    ClearingExportBatchWriter batchWriter = mock(ClearingExportBatchWriter.class);
    ClearingStructuredSchemaRegistry schemaRegistry = mock(ClearingStructuredSchemaRegistry.class);
    StructuredRecordProjector projector = mock(StructuredRecordProjector.class);
    TemplateRenderer templateRenderer = mock(TemplateRenderer.class);
    WorkerControlPlaneRuntime controlPlaneRuntime = mock(WorkerControlPlaneRuntime.class);
    ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);
    WorkerContext context = mock(WorkerContext.class);

    when(context.enabled()).thenReturn(true);
    when(context.statusPublisher()).thenReturn(StatusPublisher.NO_OP);
    when(context.config(ClearingExportWorkerConfig.class)).thenReturn(config);

    ClearingExportWorkerImpl worker = new ClearingExportWorkerImpl(
        properties,
        batchWriter,
        schemaRegistry,
        projector,
        templateRenderer,
        controlPlaneRuntime,
        applicationContext,
        new ObjectMapper().findAndRegisterModules(),
        Clock.fixed(Instant.parse("2026-02-21T00:00:00Z"), ZoneOffset.UTC));

    worker.onMessage(item, context);

    verify(templateRenderer, never()).render(anyString(), anyMap());
    verify(batchWriter, never()).append(anyString(), any(ClearingExportWorkerConfig.class));
    verify(controlPlaneRuntime).publishWorkError(anyString(), any(WorkItem.class), any(Exception.class));
    verify(applicationContext, timeout(500)).close();
  }

  @Test
  void flushFailurePublishesJournalAndStopsWorker() throws Exception {
    ClearingExportWorkerProperties properties = mock(ClearingExportWorkerProperties.class);
    ClearingExportBatchWriter batchWriter = mock(ClearingExportBatchWriter.class);
    ClearingStructuredSchemaRegistry schemaRegistry = mock(ClearingStructuredSchemaRegistry.class);
    StructuredRecordProjector projector = mock(StructuredRecordProjector.class);
    TemplateRenderer templateRenderer = mock(TemplateRenderer.class);
    WorkerControlPlaneRuntime controlPlaneRuntime = mock(WorkerControlPlaneRuntime.class);
    ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);

    org.mockito.Mockito.doThrow(new IllegalStateException("flush failure")).when(batchWriter).flushIfDue();

    ClearingExportWorkerImpl worker = new ClearingExportWorkerImpl(
        properties,
        batchWriter,
        schemaRegistry,
        projector,
        templateRenderer,
        controlPlaneRuntime,
        applicationContext,
        new ObjectMapper().findAndRegisterModules(),
        Clock.fixed(Instant.parse("2026-02-21T00:00:00Z"), ZoneOffset.UTC));

    worker.flushIfDue();

    verify(controlPlaneRuntime).publishWorkError(
        eq("clearingExportWorker"),
        argThat(item -> "clearing-export-flush".equals(String.valueOf(item.headers().get("x-ph-call-id")))),
        any(Exception.class));
    verify(applicationContext, timeout(500)).close();
  }

  @Test
  void lifecycleEventsArePublishedAsJournalOutcomesWithExpectedCallIds() throws Exception {
    ClearingExportWorkerProperties properties = mock(ClearingExportWorkerProperties.class);
    ClearingExportBatchWriter batchWriter = mock(ClearingExportBatchWriter.class);
    ClearingStructuredSchemaRegistry schemaRegistry = mock(ClearingStructuredSchemaRegistry.class);
    StructuredRecordProjector projector = mock(StructuredRecordProjector.class);
    TemplateRenderer templateRenderer = mock(TemplateRenderer.class);
    WorkerControlPlaneRuntime controlPlaneRuntime = mock(WorkerControlPlaneRuntime.class);
    ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);

    ClearingExportWorkerImpl worker = new ClearingExportWorkerImpl(
        properties,
        batchWriter,
        schemaRegistry,
        projector,
        templateRenderer,
        controlPlaneRuntime,
        applicationContext,
        new ObjectMapper().findAndRegisterModules(),
        Clock.fixed(Instant.parse("2026-02-21T00:00:00Z"), ZoneOffset.UTC));

    @SuppressWarnings("unchecked")
    org.mockito.ArgumentCaptor<Consumer<ClearingExportBatchWriter.ClearingExportLifecycleEvent>> lifecycleCaptor =
        org.mockito.ArgumentCaptor.forClass(Consumer.class);
    worker.registerPreflightStateListener();
    verify(batchWriter).setLifecycleListener(lifecycleCaptor.capture());

    lifecycleCaptor.getValue().accept(new ClearingExportBatchWriter.ClearingExportLifecycleEvent(
        ClearingExportBatchWriter.ClearingExportLifecycleEventType.CREATED,
        new ClearingExportSinkWriteResult("file.dat", 10, 200L, Instant.parse("2026-02-21T00:00:01Z"), "/tmp/file.dat"),
        null));
    lifecycleCaptor.getValue().accept(new ClearingExportBatchWriter.ClearingExportLifecycleEvent(
        ClearingExportBatchWriter.ClearingExportLifecycleEventType.WRITE_FAILED,
        null,
        new IllegalStateException("write-failed")));
    lifecycleCaptor.getValue().accept(new ClearingExportBatchWriter.ClearingExportLifecycleEvent(
        ClearingExportBatchWriter.ClearingExportLifecycleEventType.FINALIZE_FAILED,
        null,
        new IllegalStateException("finalize-failed")));
    lifecycleCaptor.getValue().accept(new ClearingExportBatchWriter.ClearingExportLifecycleEvent(
        ClearingExportBatchWriter.ClearingExportLifecycleEventType.FLUSH_SUMMARY,
        new ClearingExportSinkWriteResult("file.dat", 10, 200L, Instant.parse("2026-02-21T00:00:01Z"), "/tmp/file.dat"),
        null));

    verify(controlPlaneRuntime, times(4)).publishWorkJournalEvent(
        eq("clearingExportWorker"),
        anyString(),
        isNull(),
        eq("work-journal"),
        eq("recorded"),
        anyString(),
        isNull(),
        isNull(),
        anyMap());
    verify(controlPlaneRuntime).publishWorkJournalEvent(
        eq("clearingExportWorker"),
        anyString(),
        isNull(),
        eq("work-journal"),
        eq("recorded"),
        eq("clearing-export-created"),
        isNull(),
        isNull(),
        argThat(details -> "created".equals(String.valueOf(details.get("event")))));
    verify(controlPlaneRuntime).publishWorkJournalEvent(
        eq("clearingExportWorker"),
        anyString(),
        isNull(),
        eq("work-journal"),
        eq("recorded"),
        eq("clearing-export-write-failed"),
        isNull(),
        isNull(),
        argThat(details -> "write_failed".equals(String.valueOf(details.get("event")))));
    verify(controlPlaneRuntime).publishWorkJournalEvent(
        eq("clearingExportWorker"),
        anyString(),
        isNull(),
        eq("work-journal"),
        eq("recorded"),
        eq("clearing-export-finalize-failed"),
        isNull(),
        isNull(),
        argThat(details -> "finalize_failed".equals(String.valueOf(details.get("event")))));
    verify(controlPlaneRuntime).publishWorkJournalEvent(
        eq("clearingExportWorker"),
        anyString(),
        isNull(),
        eq("work-journal"),
        eq("recorded"),
        eq("clearing-export-flush-summary"),
        isNull(),
        isNull(),
        argThat(details -> "flush_summary".equals(String.valueOf(details.get("event")))));
    verify(controlPlaneRuntime, never()).publishWorkError(
        eq("clearingExportWorker"),
        argThat(item -> {
          Object callId = item.headers().get("x-ph-call-id");
          return "clearing-export-created".equals(String.valueOf(callId))
              || "clearing-export-write-failed".equals(String.valueOf(callId))
              || "clearing-export-finalize-failed".equals(String.valueOf(callId))
              || "clearing-export-flush-summary".equals(String.valueOf(callId));
        }),
        any(Throwable.class));
  }

  private static WorkerStateSnapshot buildSnapshot(ClearingExportWorkerConfig config, boolean enabled) throws Exception {
    WorkerDefinition definition = new WorkerDefinition(
        "clearingExportWorker",
        ClearingExportWorkerImpl.class,
        WorkerInputType.RABBITMQ,
        "clearing-export",
        WorkIoBindings.none(),
        ClearingExportWorkerConfig.class,
        WorkInputConfig.class,
        WorkOutputConfig.class,
        WorkerOutputType.NONE,
        null,
        Set.of(WorkerCapability.MESSAGE_DRIVEN));

    Constructor<WorkerState> workerStateCtor = WorkerState.class.getDeclaredConstructor(WorkerDefinition.class);
    workerStateCtor.setAccessible(true);
    WorkerState state = workerStateCtor.newInstance(definition);
    Method updateConfig = WorkerState.class.getDeclaredMethod(
        "updateConfig", Object.class, boolean.class, Boolean.class);
    updateConfig.setAccessible(true);
    updateConfig.invoke(state, config, true, enabled);

    Constructor<WorkerStateSnapshot> snapshotCtor =
        WorkerStateSnapshot.class.getDeclaredConstructor(WorkerState.class, Map.class);
    snapshotCtor.setAccessible(true);
    return snapshotCtor.newInstance(state, Map.of());
  }

  private static ClearingExportWorkerConfig testConfig() {
    return testConfigWith("latest", -1, "stop");
  }

  private static boolean hasExpectedMixedStepContext(Map<String, Object> renderContext) {
    if (renderContext.containsKey("record")) {
      return false;
    }
    if (!(renderContext.get("steps") instanceof Map<?, ?> steps)) {
      return false;
    }
    if (!(steps.get("all") instanceof List<?> all) || all.size() != 2) {
      return false;
    }
    if (!(steps.get("first") instanceof Map<?, ?> first)
        || !(first.get("json") instanceof Map<?, ?> firstJson)
        || !"request".equals(String.valueOf(firstJson.get("phase")))) {
      return false;
    }
    if (!(steps.get("latest") instanceof Map<?, ?> latest)
        || !(latest.get("json") instanceof Map<?, ?> latestJson)
        || !"response".equals(String.valueOf(latestJson.get("phase")))) {
      return false;
    }
    if (!(steps.get("selected") instanceof Map<?, ?> selected)
        || !(selected.get("json") instanceof Map<?, ?> selectedStepJson)
        || !"00".equals(String.valueOf(selectedStepJson.get("responseCode")))) {
      return false;
    }
    if (!(steps.get("previous") instanceof Map<?, ?> previous)
        || !(previous.get("json") instanceof Map<?, ?> previousJson)
        || !"request".equals(String.valueOf(previousJson.get("phase")))) {
      return false;
    }
    if (!(steps.get("byIndex") instanceof Map<?, ?> byIndex)
        || !(byIndex.get("0") instanceof Map<?, ?> idx0)
        || !(idx0.get("json") instanceof Map<?, ?> idx0Json)
        || !"request".equals(String.valueOf(idx0Json.get("phase")))
        || !(byIndex.get("1") instanceof Map<?, ?> idx1)
        || !(idx1.get("json") instanceof Map<?, ?> idx1Json)
        || !"00".equals(String.valueOf(idx1Json.get("responseCode")))) {
      return false;
    }
    return Integer.valueOf(2).equals(steps.get("count"))
        && Integer.valueOf(1).equals(steps.get("selectedIndex"));
  }

  private static ClearingExportWorkerConfig testConfigWith(
      String recordSourceStep,
      int recordSourceStepIndex,
      String recordBuildFailurePolicy
  ) {
    return new ClearingExportWorkerConfig(
        "template",
        true,
        2_000L,
        1_000,
        1_000,
        50_000,
        true,
        "\n",
        "stream.dat",
        "H|{{ now }}",
        "D|{{ steps.selected.payload }}",
        "T|{{ recordCount }}",
        "/tmp/out",
        ".tmp",
        false,
        "reports/clearing/manifest.jsonl",
        "/tmp/schemas",
        null,
        null,
        recordSourceStep,
        recordSourceStepIndex,
        recordBuildFailurePolicy
    );
  }

  private static ClearingExportWorkerConfig structuredConfigWith(
      String recordSourceStep,
      int recordSourceStepIndex,
      String recordBuildFailurePolicy
  ) {
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
        "/tmp/schemas",
        "pcs",
        "1.0.0",
        recordSourceStep,
        recordSourceStepIndex,
        recordBuildFailurePolicy
    );
  }
}
