package io.pockethive.clearingexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.pockethive.templating.PebbleTemplateRenderer;
import io.pockethive.templating.api.DisabledSequenceAccess;
import io.pockethive.templating.api.TemplateRenderer;
import io.pockethive.work.api.StatusPublisher;
import io.pockethive.work.api.WorkItem;
import io.pockethive.work.api.WorkerContext;
import io.pockethive.work.api.WorkerInfo;
import io.pockethive.worker.sdk.runtime.WorkerControlPlaneRuntime;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;

class ClearingTemplateConsumerTest {
  private static final WorkerInfo INFO = new WorkerInfo("clearing", "swarm", "instance", null, null);
  private final TemplateRenderer renderer = new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE);
  private final ClearingExportBatchWriter batchWriter = mock(ClearingExportBatchWriter.class);
  private final ClearingStructuredSchemaRegistry registry = mock(ClearingStructuredSchemaRegistry.class);
  private final WorkerControlPlaneRuntime runtime = mock(WorkerControlPlaneRuntime.class);

  @ParameterizedTest
  @ValueSource(strings = {
      "{\"id\":\"000123\",\"id\":\"000456\"}",
      "{\"nested\":{\"id\":1,\"id\":2}}",
      "{\"id\":\"000123\"} {}", "{\"id\":\"000123\"} trailing", "{\"id\":\"000123\"",
      "<card id=\"000123\"/>", "plain 000123", "", "  ", "null", "[1,2]", "123", "\"000123\""
  })
  void optionalStepJsonIsAbsentWithoutChangingTheRawPayload(String payload) throws Exception {
    var config = config("{% if steps.selected.json is null %}absent{% else %}projected{% endif %}|{{ steps.selected.payload }}");

    render(payload, config);

    verify(batchWriter).append("absent|" + payload, config);
  }

  @ParameterizedTest
  @MethodSource("existingTemplateDocuments")
  void rendersTheExistingClearingExampleWithoutPreparsedInvalidDocuments(String payload, String expected) throws Exception {
    var example = new ObjectMapper(new YAMLFactory()).readTree(Path.of("examples/clearing-template-basic.yaml").toFile());
    var config = config(example.path("recordTemplate").textValue());

    render(payload, config);

    verify(batchWriter).append(expected, config);
  }

  static Stream<Arguments> existingTemplateDocuments() {
    return Stream.of(
        Arguments.of("{\"clearingId\":\"000123\",\"panMasked\":\"0000****0001\",\"amountMinor\":\"000045\",\"currency\":\"032\",\"responseCode\":\"00\"}",
            "D|000123|0000****0001|000045|032|00"),
        Arguments.of("{\"clearingId\":\"000123\",\"clearingId\":\"000456\"}", "D|||||"),
        Arguments.of("{\"clearingId\":\"000123\"} {}", "D|||||"));
  }

  @Test
  void existingStructuredSchemaReceivesTheSameStepProjectionAndPreservesStringIds() throws Exception {
    var schema = new ObjectMapper().readValue(
        Path.of("../scenarios/acceptance/clearing-xml-rabbit/clearing-schemas/acceptance/1/schema.json").toFile(),
        ClearingStructuredSchema.class);
    var config = config("unused");
    when(config.structuredMode()).thenReturn(true);
    when(registry.resolve(config)).thenReturn(schema);

    render("{\"id\":\"000123\",\"amount\":45}", config);

    verify(batchWriter).appendStructured(argThat(record -> "000123".equals(record.values().get("id"))), eq(config), eq(schema));
    verify(batchWriter, never()).append(any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"{\"id\":\"000123\",\"id\":\"000456\",\"amount\":45}", "{\"id\":\"000123\",\"amount\":45} {}"})
  void existingStructuredRequiredFieldsCannotUsePreparsedInvalidDocuments(String payload) throws Exception {
    var schema = new ObjectMapper().readValue(
        Path.of("../scenarios/acceptance/clearing-xml-rabbit/clearing-schemas/acceptance/1/schema.json").toFile(),
        ClearingStructuredSchema.class);
    var config = config("unused");
    when(config.structuredMode()).thenReturn(true);
    when(config.recordBuildFailurePolicyMode()).thenReturn(ClearingExportWorkerConfig.RecordBuildFailurePolicy.SKIP_RECORD);
    when(registry.resolve(config)).thenReturn(schema);

    render(payload, config);

    verify(batchWriter, never()).appendStructured(any(), any(), any());
    verify(batchWriter, never()).append(any(), any());
  }

  private ClearingExportWorkerConfig config(String template) {
    var config = mock(ClearingExportWorkerConfig.class);
    when(config.sourceStepMode()).thenReturn(ClearingExportWorkerConfig.RecordSourceStep.LATEST);
    when(config.recordTemplate()).thenReturn(template);
    return config;
  }

  private void render(String payload, ClearingExportWorkerConfig config) {
    var worker = new ClearingExportWorkerImpl(
        mock(ClearingExportWorkerProperties.class), batchWriter, registry, new StructuredRecordProjector(renderer),
        renderer, runtime, mock(ConfigurableApplicationContext.class));
    var context = mock(WorkerContext.class);
    when(context.requireConfig(ClearingExportWorkerConfig.class)).thenReturn(config);
    when(context.statusPublisher()).thenReturn(StatusPublisher.NO_OP);
    WorkItem item = WorkItem.text(INFO, payload).build();

    assertThat(worker.onMessage(item, context)).isNull();
    assertThat(item.payload()).isEqualTo(payload);
    verify(runtime, never()).publishWorkError(any(), any(), any());
  }
}
