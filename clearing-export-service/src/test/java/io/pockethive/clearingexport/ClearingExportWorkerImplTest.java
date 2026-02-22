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
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
  void onMessageFailureDoesNotPublishManualWorkError() throws Exception {
    ClearingExportWorkerProperties properties = mock(ClearingExportWorkerProperties.class);
    ClearingExportBatchWriter batchWriter = mock(ClearingExportBatchWriter.class);
    ClearingStructuredSchemaRegistry schemaRegistry = mock(ClearingStructuredSchemaRegistry.class);
    StructuredRecordProjector projector = mock(StructuredRecordProjector.class);
    TemplateRenderer templateRenderer = mock(TemplateRenderer.class);
    WorkerControlPlaneRuntime controlPlaneRuntime = mock(WorkerControlPlaneRuntime.class);
    ConfigurableApplicationContext applicationContext = mock(ConfigurableApplicationContext.class);
    WorkerContext context = mock(WorkerContext.class);
    ClearingExportWorkerConfig config = testConfig();

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

    assertThatThrownBy(() -> worker.onMessage(WorkItem.text("{\"id\":1}").build(), context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to append clearing export record");

    verify(batchWriter, never()).preflight(any(ClearingExportWorkerConfig.class));
    verify(controlPlaneRuntime, never()).publishWorkError(anyString(), any(WorkItem.class), any(Throwable.class));
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
        "D|{{ record.payload }}",
        "T|{{ recordCount }}",
        "/tmp/out",
        ".tmp",
        false,
        "reports/clearing/manifest.jsonl",
        "/tmp/schemas",
        null,
        null
    );
  }
}
