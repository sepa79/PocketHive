package io.pockethive.clearingexport;

import io.pockethive.worker.sdk.templating.PebbleTemplateRenderer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClearingExportFileAssemblerTest {

  @Test
  void assemblesHeaderRecordsAndFooterUsingTemplates() {
    Clock fixed = Clock.fixed(Instant.parse("2026-02-18T10:15:30Z"), ZoneOffset.UTC);
    ClearingExportFileAssembler assembler =
        new ClearingExportFileAssembler(new PebbleTemplateRenderer(), fixed);

    ClearingExportWorkerConfig config = new ClearingExportWorkerConfig(
        10,
        1_000,
        100,
        true,
        "\n",
        "out_{{ recordCount }}.txt",
        "H|{{ now }}",
        "D|{{ record.payload }}",
        "T|{{ recordCount }}",
        "/tmp/out",
        ".tmp",
        false,
        "reports/manifest.jsonl"
    );

    ClearingRenderedFile rendered = assembler.assemble(config, List.of("D|one", "D|two"));

    assertThat(rendered.fileName()).isEqualTo("out_2.txt");
    assertThat(rendered.recordCount()).isEqualTo(2);
    assertThat(rendered.content()).isEqualTo(
        "H|2026-02-18T10:15:30Z\n" +
            "D|one\n" +
            "D|two\n" +
            "T|2\n");
  }
}
