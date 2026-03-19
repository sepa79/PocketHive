package io.pockethive.clearingexport;

import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import io.pockethive.worker.sdk.templating.TemplateRenderer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ClearingExportBatchWriterBeanWiringTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withBean(TemplateRenderer.class, PebbleTemplateRenderer::new)
      .withBean(XmlOutputFormatter.class, XmlOutputFormatter::new)
      .withBean(ClearingExportFileAssembler.class)
      .withBean(ClearingExportSink.class, LocalDirectoryClearingExportSink::new)
      .withBean(ClearingExportBatchWriter.class);

  @Test
  void createsBatchWriterBeanFromCollaborators() {
    contextRunner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).hasSingleBean(ClearingExportBatchWriter.class);
    });
  }
}
