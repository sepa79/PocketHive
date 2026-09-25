package io.pockethive.clearingexport;

import io.pockethive.controlplane.filesystem.RuntimeOutputDirectory;

import io.pockethive.templating.api.DisabledSequenceAccess;

import io.pockethive.templating.PebbleTemplateRenderer;
import io.pockethive.templating.api.TemplateRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ClearingExportBatchWriterBeanWiringTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withBean(TemplateRenderer.class, () -> new PebbleTemplateRenderer(DisabledSequenceAccess.INSTANCE))
      .withBean(XmlOutputFormatter.class, XmlOutputFormatter::new)
      .withBean(ClearingExportFileAssembler.class)
      .withBean(ClearingExportSink.class, () -> new LocalDirectoryClearingExportSink(new RuntimeOutputDirectory(java.nio.file.Path.of("/tmp/clearing-wiring-test"))))
      .withBean(ClearingExportBatchWriter.class);

  @Test
  void createsBatchWriterBeanFromCollaborators() {
    contextRunner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).hasSingleBean(ClearingExportBatchWriter.class);
    });
  }
}
